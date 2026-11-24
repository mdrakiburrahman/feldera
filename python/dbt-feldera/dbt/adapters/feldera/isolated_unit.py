import threading
import time
import uuid

import agate
import sqlglot
from sqlglot import exp

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.pipeline_manager import PipelineStateManager, _admit_start, _start_admitted
from feldera.enums import CompilationProfile, PipelineStatus, ProgramStatus
from feldera.pipeline import Pipeline
from feldera.pipeline_builder import PipelineBuilder
from feldera.rest.errors import FelderaAPIError
from feldera.runtime_config import RuntimeConfig

_admission_context = threading.local()


def is_isolated_unit_pipeline(pipeline):
    """Identify the exact validated scratch instance during its admission callback."""
    return pipeline is not None and getattr(_admission_context, "pipeline", None) is pipeline


def _query_only(sql):
    statements = [stmt for stmt in sqlglot.parse(sql) if stmt is not None]
    if len(statements) != 1 or not isinstance(statements[0], exp.Query):
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
    if info["input_connectors"] or info["output_connectors"] or schema["inputs"]:
        raise RuntimeError("Unit test scratch pipeline must not contain input tables or connectors")
    outputs = {view["name"]: view for view in schema["outputs"]}
    if set(outputs) != expected_views | {"error_view"}:
        raise RuntimeError("Unit test scratch pipeline contains unexpected views")
    return outputs


def _sql_type(column):
    t = column["columntype"]
    name = t["type"]
    if name in ("DECIMAL", "NUMERIC") and t.get("precision", -1) > 0:
        return f"{name}({t['precision']},{t.get('scale', 0)})"
    return name


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


def run_unit_test(client, sql, expected_rows, expected_sql, render_expected, udf_sql, udf_rust, udf_toml, timeout):
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
            runtime_config=RuntimeConfig.from_dict({"workers": 1}),
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
            expected_sql = render_expected(expected_rows, types, quoted)
        _query_only(expected_sql)
        expected = (
            "CREATE MATERIALIZED VIEW dbt_unit_expected AS "
            "WITH dbt_internal_unit_test_actual AS (SELECT * FROM dbt_unit_actual) "
            + expected_sql.rstrip().rstrip(";")
            + ";"
        )
        pipeline.modify(sql=actual_sql + "\n\n" + expected)
        PipelineStateManager._wait_for_compilation(pipeline, timeout)
        _inspect(pipeline, {"dbt_unit_actual", "dbt_unit_expected"})
        previous = getattr(_admission_context, "pipeline", None)
        _admission_context.pipeline = pipeline
        try:
            admitted = _admit_start(pipeline)
        finally:
            _admission_context.pipeline = previous
        _start_admitted(pipeline, admitted)
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
