import re
import time
import uuid
from copy import deepcopy
from datetime import datetime

import agate
import sqlglot
from sqlglot import exp
from sqlglot.dialects.dialect import Dialect
from sqlglot.tokens import Tokenizer, TokenType

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.column import _char_sql_type
from dbt.adapters.feldera.pipeline_manager import PipelineStateManager
from feldera.enums import CompilationProfile, PipelineStatus, ProgramStatus
from feldera.pipeline import Pipeline
from feldera.pipeline_builder import PipelineBuilder
from feldera.rest.errors import FelderaAPIError
from feldera.runtime_config import RuntimeConfig

_NOW_INPUT = {
    "name": "now",
    "case_sensitive": False,
    "fields": [
        {
            "name": "now",
            "case_sensitive": False,
            "columntype": {"type": "TIMESTAMP", "nullable": False, "precision": 6},
            "unused": False,
        }
    ],
    "foreign_keys": [],
    "materialized": False,
}


class _UnitDialect(Dialect):
    class Tokenizer(Tokenizer):
        KEYWORDS = {**Tokenizer.KEYWORDS, "ROW": TokenType.STRUCT}


def _query_only(sql):
    statements = [stmt for stmt in sqlglot.parse(sql, dialect=_UnitDialect) if stmt is not None]
    if (
        len(statements) != 1
        or not isinstance(statements[0], exp.Query)
        or any(statements[0].find_all(exp.DML, exp.DDL, exp.Into, exp.Command))
    ):
        raise ValueError("A dbt unit test must contain exactly one query")


def _wait_for_sql(pipeline, timeout):
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        status = pipeline.program_status()
        if status in (ProgramStatus.SqlCompiled, ProgramStatus.CompilingRust, ProgramStatus.Success):
            return
        if status not in (ProgramStatus.Pending, ProgramStatus.CompilingSql):
            raise RuntimeError(f"Unit test SQL compilation failed: {status}: {pipeline.program_error()}")
        time.sleep(0.2)
    raise TimeoutError("Unit test SQL compilation timed out")


def _inspect(pipeline, expected_views):
    info = pipeline.program_info()
    schema = info["schema"]
    if info["input_connectors"] or info["output_connectors"] or schema["inputs"] not in ([], [_NOW_INPUT]):
        raise RuntimeError("Unit test scratch pipeline must not contain input tables or connectors")
    outputs = {view["name"]: view for view in schema["outputs"]}
    if set(outputs) != expected_views | {"error_view"}:
        raise RuntimeError("Unit test scratch pipeline contains unexpected views")
    return outputs


def _sql_type(column):
    t = column["columntype"]
    name = t["type"]
    if name == "CHAR":
        return _char_sql_type(t.get("precision"))
    if name in ("DECIMAL", "NUMERIC") and t.get("precision", -1) > 0:
        return f"{name}({t['precision']},{t.get('scale', 0)})"
    return name


def _normalize_timestamp_literal(value):
    if not isinstance(value, str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?", value
    ):
        return value
    try:
        datetime.fromisoformat(value[:17] + "59" + value[19:] if value[17:19] == "60" else value)
    except ValueError:
        return value
    return value[:10] + " " + value[11:]


def _normalize_expected_rows(rows, types):
    return [
        {
            name: _normalize_timestamp_literal(value) if types.get(name.lower()) == "TIMESTAMP" else value
            for name, value in row.items()
        }
        for row in rows
    ]


def _agate_type(column):
    name = column["columntype"]["type"]
    if name in ("BOOLEAN",):
        return agate.Boolean()
    if name in ("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "DECIMAL", "NUMERIC", "REAL", "DOUBLE"):
        return agate.Number()
    if name in ("VARCHAR", "CHAR", "STRING", "TEXT"):
        return agate.Text()
    if name == "DATE":
        return agate.Date()
    if name == "TIMESTAMP":
        return agate.DateTime()
    raise ValueError(f"Unsupported unit test result type: {name}")


def _unit_runtime_config(runtime_config):
    config = deepcopy(runtime_config or {})
    config.setdefault("workers", 1)
    return RuntimeConfig.from_dict(config)


def run_unit_test(
    client, sql, expected_rows, expected_sql, render_expected, udf_sql, udf_rust, udf_toml, timeout, runtime_config=None
):
    _query_only(sql)
    name = "dbt_unit_" + uuid.uuid4().hex
    description = "dbt unit test " + uuid.uuid4().hex
    prefix = (udf_sql.strip() + "\n\n") if udf_sql.strip() else ""
    actual_sql = prefix + "CREATE MATERIALIZED VIEW dbt_unit_actual AS " + sql.rstrip().rstrip(";") + ";"
    pipeline = None
    try:
        pipeline = PipelineBuilder(
            client,
            name=name,
            sql=actual_sql,
            udf_rust=udf_rust,
            udf_toml=udf_toml,
            description=description,
            compilation_profile=CompilationProfile.DEV,
            runtime_config=_unit_runtime_config(runtime_config),
        ).create(wait=False)
        _wait_for_sql(pipeline, timeout)
        actual = _inspect(pipeline, {"dbt_unit_actual"})["dbt_unit_actual"]
        fields = actual["fields"]
        names = {field["name"].lower(): field for field in fields}
        if len(names) != len(fields):
            raise ValueError("Unit test output has duplicate column names")
        requested = list(expected_rows[0]) if expected_rows else [field["name"] for field in fields]
        selected = [names[name.lower()] for name in requested]
        types = {name: _sql_type(field) for name, field in names.items()}
        quoted = {name: '"' + field["name"].replace('"', '""') + '"' for name, field in names.items()}
        if not expected_sql:
            expected_sql = render_expected(_normalize_expected_rows(expected_rows, types), types, quoted)
        _query_only(expected_sql)
        expected = (
            "CREATE MATERIALIZED VIEW dbt_unit_expected AS "
            "WITH dbt_internal_unit_test_actual AS (SELECT * FROM dbt_unit_actual) "
            "SELECT * FROM (\n" + expected_sql.rstrip().rstrip(";") + "\n) AS dbt_unit_fixture;"
        )
        pipeline.modify(sql=actual_sql + "\n\n" + expected)
        PipelineStateManager._wait_for_compilation(pipeline, timeout)
        _inspect(pipeline, {"dbt_unit_actual", "dbt_unit_expected"})
        pipeline.start()
        if pipeline.program_info()["schema"]["inputs"]:
            started = time.monotonic()
            while not pipeline.stats().global_metrics.total_processed_records:
                if time.monotonic() - started >= timeout:
                    raise TimeoutError("Unit test clock did not advance")
                time.sleep(0.2)
            pipeline.pause()
        pipeline.wait_for_idle(timeout_s=timeout)
        columns = [field["name"] for field in selected]
        projection = ", ".join(quoted[name.lower()] for name in columns)
        rows = []
        for relation, kind in (("dbt_unit_actual", "actual"), ("dbt_unit_expected", "expected")):
            rows.extend(
                [
                    tuple(row[column] for column in columns) + (kind,)
                    for row in pipeline.query(f"SELECT {projection} FROM {relation}")
                ]
            )
        table = agate.Table(rows, columns + ["actual_or_expected"], [_agate_type(f) for f in selected] + [agate.Text()])
        return AdapterResponse(_message="OK"), table
    finally:
        if pipeline is None:
            try:
                candidate = Pipeline.get(name, client)
            except FelderaAPIError as exc:
                if exc.status_code != 404:
                    raise
            else:
                if candidate.description() == description:
                    pipeline = candidate
        if pipeline is not None:
            if pipeline.status() != PipelineStatus.STOPPED:
                pipeline.stop(force=True)
            pipeline.delete(clear_storage=True)
