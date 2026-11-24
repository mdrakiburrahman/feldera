import hashlib
import json
import math
import struct
from collections import Counter

import pyarrow as pa
import pyarrow.compute as pc

_hook = None


def set_seed_verification_hook(callback):
    """Set or clear the process-local callback for verified seed evidence."""
    global _hook
    if callback is not None and not callable(callback):
        raise TypeError("Seed verification hook must be callable or None")
    _hook = callback


def get_seed_verification_hook():
    return _hook


def snapshot(client, pipeline_name, seed_names):
    descriptor = client.http.get(f"/pipelines/{pipeline_name}")
    stats = client.get_pipeline_stats(pipeline_name)
    keys = ("id", "version", "program_version", "platform_version", "deployment_id")
    identity = {key: descriptor.get(key) for key in keys}
    if (
        not isinstance(identity["id"], str)
        or not identity["id"]
        or type(identity["version"]) is not int
        or type(identity["program_version"]) is not int
        or not isinstance(identity["platform_version"], str)
        or not identity["platform_version"]
        or not isinstance(identity["deployment_id"], str)
        or not identity["deployment_id"]
        or descriptor.get("deployment_status") != "Running"
        or descriptor.get("program_status") != "Success"
    ):
        raise RuntimeError(f"Pipeline '{pipeline_name}' is not a compiled running incarnation")
    metrics = stats.get("global_metrics")
    if (
        not isinstance(metrics, dict)
        or not isinstance(metrics.get("incarnation_uuid"), str)
        or not metrics["incarnation_uuid"]
    ):
        raise RuntimeError(f"Pipeline '{pipeline_name}' has no stats incarnation")
    identity["incarnation_uuid"] = metrics["incarnation_uuid"]
    identity["stats_start_time"] = metrics.get("start_time")
    if identity["stats_start_time"] is None:
        raise RuntimeError(f"Pipeline '{pipeline_name}' has no stats start time")
    identity["program_sha256"] = _sha(
        _json({key: descriptor.get(key) for key in ("program_code", "udf_rust", "udf_toml", "program_config")})
    )
    inputs = stats.get("inputs")
    if not isinstance(inputs, list):
        raise RuntimeError(f"Pipeline '{pipeline_name}' has no input statistics")
    observed = {}
    for endpoint in inputs:
        stream = endpoint.get("config", {}).get("stream")
        if stream not in seed_names:
            continue
        endpoint_name = endpoint.get("endpoint_name")
        counters = endpoint.get("metrics")
        if (
            not isinstance(endpoint_name, str) or not endpoint_name or not isinstance(counters, dict)
            or type(counters.get("total_records")) is not int or counters["total_records"] < 0
        ):
            raise RuntimeError(f"Invalid input statistics for seed '{stream}'")
        if (
            endpoint.get("fatal_error")
            or endpoint.get("parse_errors")
            or endpoint.get("transport_errors")
            or any(
                type(counters.get(key)) is not int or counters[key] != 0
                for key in ("num_parse_errors", "num_transport_errors")
            )
        ):
            raise RuntimeError(f"Input errors for seed '{stream}'")
        if endpoint_name in observed:
            raise RuntimeError(f"Duplicate input endpoint '{endpoint_name}'")
        observed[endpoint_name] = {"stream": stream, "metrics": counters}
    return identity, observed, descriptor


def summarize_inputs(inputs):
    return [
        {"stream": entry["stream"], "endpoint_name": name, "total_records": entry["metrics"]["total_records"]}
        for name, entry in sorted(inputs.items())
    ]


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def _cell(value):
    if not value.is_valid:
        return None
    dtype = value.type
    if pa.types.is_boolean(dtype):
        return ["bool", value.as_py()]
    if pa.types.is_integer(dtype):
        return ["int", str(value.as_py())]
    if pa.types.is_floating(dtype):
        number = value.as_py()
        if not math.isfinite(number):
            raise ValueError("Non-finite floating point seed value")
        if pa.types.is_float32(dtype):
            return ["float32", struct.pack(">f", number).hex()]
        if pa.types.is_float64(dtype):
            return ["float64", struct.pack(">d", number).hex()]
    if pa.types.is_decimal(dtype):
        return ["decimal", format(value.as_py(), "f")]
    if pa.types.is_string(dtype) or pa.types.is_large_string(dtype):
        return ["string", value.as_py()]
    if pa.types.is_binary(dtype) or pa.types.is_large_binary(dtype):
        return ["binary", value.as_py().hex()]
    if pa.types.is_date(dtype) or pa.types.is_time(dtype) or pa.types.is_timestamp(dtype):
        return ["temporal", str(value.value)]
    raise ValueError(f"Unsupported seed digest type: {dtype}")


def _schema(schema, compiled_fields):
    names = [field.name.casefold() for field in schema]
    if (
        len(set(names)) != len(names)
        or names != [field["name"].casefold() for field in compiled_fields]
    ):
        raise ValueError("Compiled and Arrow seed schemas differ")
    result = []
    for field, compiled in zip(schema, compiled_fields):
        dtype = field.type
        if not (
            pa.types.is_boolean(dtype) or pa.types.is_integer(dtype)
            or pa.types.is_float32(dtype) or pa.types.is_float64(dtype)
            or pa.types.is_decimal(dtype) or pa.types.is_string(dtype) or pa.types.is_large_string(dtype)
            or pa.types.is_binary(dtype) or pa.types.is_large_binary(dtype)
            or pa.types.is_date(dtype) or pa.types.is_time(dtype) or pa.types.is_timestamp(dtype)
        ):
            raise ValueError(f"Unsupported seed digest type: {dtype}")
        result.append({
            "name": field.name,
            "feldera_name": compiled["name"],
            "arrow_type": str(dtype),
            "feldera_type": compiled["columntype"],
        })
    return result


def _digest(schema, compiled_fields, rows):
    fields = _schema(schema, compiled_fields)
    hashes = Counter(_sha(_json([_cell(cell) for cell in row])) for row in rows)
    multiplicities = [{"sha256": digest, "count": count} for digest, count in sorted(hashes.items())]
    value = {"version": 1, "schema": fields, "rows": multiplicities}
    return {
        "version": 1,
        "schema": fields,
        "row_count": sum(hashes.values()),
        "row_sha256_multiplicities": multiplicities,
        "multiset_sha256": _sha(_json(value)),
    }


def verify_seed_digest(schema, compiled_fields, batches, expected_digest):
    """Recompute a v1 receipt digest from an independently queried Arrow stream."""
    rows = (tuple(column[index] for column in batch.columns) for batch in batches for index in range(batch.num_rows))
    return _digest(schema, compiled_fields, rows) == expected_digest


def verify_and_digest(pipeline, table_name, expected_rows, descriptor):
    quoted = '"' + table_name.replace('"', '""') + '"'
    response = pipeline.client.http.get(
        path=f"/pipelines/{pipeline.name}/query",
        params={"pipeline_name": pipeline.name, "sql": f"SELECT * FROM {quoted}", "format": "arrow_ipc"},
        stream=True,
    )
    try:
        with pa.ipc.open_stream(response.raw) as reader:
            schema = reader.schema
            inputs = descriptor["program_info"]["schema"]["inputs"]
            matching = [table for table in inputs if table["name"] == table_name]
            if len(matching) != 1:
                raise RuntimeError(f"Seed '{table_name}' is missing from compiled schema")
            fields = matching[0]["fields"]
            _schema(schema, fields)
            arrow_names = [field.name.casefold() for field in schema]
            expected_names = {name.casefold(): name for name in (expected_rows[0] if expected_rows else ())}
            if expected_rows and (
                len(expected_names) != len(expected_rows[0]) or set(expected_names) != set(arrow_names)
            ):
                raise RuntimeError(f"Seed schema differs for '{table_name}'; a cold rebuild is required")
            expected_rows_typed = (
                tuple(
                    pc.cast(pa.scalar(row[expected_names[field.name.casefold()]]), field.type, safe=True)
                    for field in schema
                )
                for row in expected_rows
            )
            expected = _digest(schema, fields, expected_rows_typed)
            actual_rows = (
                tuple(column[index] for column in batch.columns)
                for batch in reader for index in range(batch.num_rows)
            )
            actual = _digest(schema, fields, actual_rows)
            if actual != expected:
                raise RuntimeError(f"Seed data differs for '{table_name}'; a cold rebuild is required")
            return {
                "expected": expected,
                "observed": actual,
            }
    finally:
        response.close()
