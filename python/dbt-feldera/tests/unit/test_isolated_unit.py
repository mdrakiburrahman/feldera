from unittest.mock import Mock, patch

import agate
import pytest
from feldera.enums import PipelineStatus
from sqlglot.errors import ParseError

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.impl import FelderaAdapter
from dbt.adapters.feldera.isolated_unit import (
    _NOW_INPUT,
    _inspect,
    _normalize_expected_rows,
    _query_only,
    _sql_type,
    _unit_runtime_config,
    run_unit_test,
)
from dbt.cli.main import dbtRunner


def test_char_output_type_preserves_width():
    assert _sql_type({"columntype": {"type": "CHAR", "precision": 5}}) == "CHAR(5)"


def test_inspect_accepts_only_the_verified_intrinsic_now_input():
    info = _info(["dbt_unit_actual"])
    info["schema"]["inputs"] = [
        {
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
    ]
    pipeline = Mock()
    pipeline.program_info.return_value = info
    assert "dbt_unit_actual" in _inspect(pipeline, {"dbt_unit_actual"})
    info["schema"]["inputs"][0]["fields"][0]["columntype"]["nullable"] = True
    with pytest.raises(RuntimeError, match="input tables or connectors"):
        _inspect(pipeline, {"dbt_unit_actual"})


@pytest.mark.parametrize(
    ("sizes", "expected"),
    [
        ({}, {"storage_mb_min": 200000, "storage_mb_max": 200000}),
        ({"storage_mb_min": 1000, "storage_mb_max": 400000}, {"storage_mb_min": 200000, "storage_mb_max": 400000}),
        ({"storage_min": "4Gi", "storage_max": "400G"}, {"storage_min": "200000M", "storage_max": "400G"}),
    ],
)
def test_scratch_runtime_inherits_storage_without_mutating_project(sizes, expected):
    from copy import deepcopy

    source = {
        "storage": {"compression": "default"},
        "resources": {**sizes, "storage_class": "premium-v2", "volume_attributes_class": "iops-80000"},
        "workers": 2,
    }
    before = deepcopy(source)
    result = _unit_runtime_config(source)
    assert result.to_dict()["storage"] == source["storage"]
    assert result.to_dict()["resources"] == {
        **expected,
        "storage_class": "premium-v2",
        "volume_attributes_class": "iops-80000",
    }
    assert result.workers == 2
    assert source == before


@pytest.mark.parametrize("precision", [None, -1, 0, "5", True])
def test_char_output_type_rejects_unknown_width(precision):
    with pytest.raises(ValueError, match="CHAR column has unknown or invalid width"):
        _sql_type({"columntype": {"type": "CHAR", "precision": precision}})


def test_expected_timestamp_normalization_is_typed_and_preserves_values():
    rows = [
        {"Ts": "2026-02-02T00:00:00.123456789", "text": "2026-02-02T00:00:00.123456789"},
        {"Ts": "2024-12-31T23:59:60"},
        {"Ts": "2026-02-02T00:00:00+02:00"},
        {"Ts": "2026-02-30T00:00:00"},
        {"Ts": None},
    ]
    assert _normalize_expected_rows(rows, {"ts": "TIMESTAMP", "text": "VARCHAR"}) == [
        {"Ts": "2026-02-02 00:00:00.123456789", "text": "2026-02-02T00:00:00.123456789"},
        {"Ts": "2024-12-31 23:59:60"},
        {"Ts": "2026-02-02T00:00:00+02:00"},
        {"Ts": "2026-02-30T00:00:00"},
        {"Ts": None},
    ]
    assert rows[0]["Ts"] == "2026-02-02T00:00:00.123456789"


def test_query_validation_supports_named_row_cast():
    _query_only("select cast(row('alpha', 7) as row(name varchar, amount integer)) as value")
    _query_only("select cast(row(';drop table t', 7) as row(name varchar, amount integer)) as value")


@pytest.mark.parametrize(
    "sql",
    [
        "select 1; drop table t",
        "select 1 into t",
        "with changed as (delete from t returning *) select * from changed",
        "select cast(row('x') as row(name NO_TYPE))",
    ],
)
def test_query_validation_rejects_mutation_and_unparsed_sql(sql):
    with pytest.raises((ValueError, ParseError)):
        _query_only(sql)


def _info(outputs, inputs=None):
    return {
        "input_connectors": inputs or {},
        "output_connectors": {},
        "schema": {
            "inputs": [],
            "outputs": [{"name": "error_view", "fields": []}]
            + [{"name": output, "fields": [{"name": "id", "columntype": {"type": "INTEGER"}}]} for output in outputs],
        },
    }


@pytest.mark.parametrize("clock", [False, True])
def test_scratch_preserves_duplicates_nulls_and_cleans_up(clock):
    pipeline = Mock()
    pipeline.status.return_value = PipelineStatus.STOPPED
    actual_info = _info(["dbt_unit_actual"])
    final_info = _info(["dbt_unit_actual", "dbt_unit_expected"])
    if clock:
        actual_info["schema"]["inputs"] = [_NOW_INPUT]
        final_info["schema"]["inputs"] = [_NOW_INPUT]
        pipeline.stats.return_value.global_metrics.total_processed_records = 1
    pipeline.program_info.side_effect = [actual_info, final_info, final_info]
    pipeline.query.side_effect = [[{"id": 1}, {"id": 1}, {"id": None}], [{"id": 1}, {"id": None}]]
    with (
        patch("dbt.adapters.feldera.isolated_unit.PipelineBuilder") as builder,
        patch("dbt.adapters.feldera.isolated_unit._wait_for_sql"),
        patch("dbt.adapters.feldera.isolated_unit.PipelineStateManager._wait_for_compilation"),
    ):
        builder.return_value.create.return_value = pipeline
        _, table = run_unit_test(Mock(), "select 1 as id", [{"id": 1}], "", lambda *_: "select 1 as id", "", "", "", 30)
    assert len(table.rows) == 5
    pipeline.start.assert_called_once_with()
    if clock:
        pipeline.pause.assert_called_once_with()
    else:
        pipeline.pause.assert_not_called()
    pipeline.wait_for_idle.assert_called_once_with(timeout_s=30)
    pipeline.delete.assert_called_once_with(clear_storage=True)


@pytest.mark.parametrize("stage", ["connector", "physical_input", "invalid_expected"])
def test_scratch_failure_never_starts_and_cleans_up(stage):
    pipeline = Mock()
    pipeline.status.return_value = PipelineStatus.STOPPED
    final_info = _info(["dbt_unit_actual", "dbt_unit_expected"], {"unsafe": {}} if stage == "connector" else None)
    if stage == "physical_input":
        final_info["schema"]["inputs"] = [{"name": "user_table", "fields": []}]
    pipeline.program_info.side_effect = [
        _info(["dbt_unit_actual"]),
        final_info,
    ]
    with (
        patch("dbt.adapters.feldera.isolated_unit.PipelineBuilder") as builder,
        patch("dbt.adapters.feldera.isolated_unit._wait_for_sql"),
        patch("dbt.adapters.feldera.isolated_unit.PipelineStateManager._wait_for_compilation"),
    ):
        builder.return_value.create.return_value = pipeline
        expected = "drop table foo" if stage == "invalid_expected" else "select 1 as id"
        with pytest.raises((ValueError, RuntimeError)):
            run_unit_test(Mock(), "select 1 as id", [{"id": 1}], expected, None, "", "", "", 30)
    pipeline.start.assert_not_called()
    pipeline.delete.assert_called_once_with(clear_storage=True)


@pytest.mark.parametrize("owned", [True, False])
def test_ambiguous_creation_only_cleans_up_owned_pipeline(owned):
    pipeline = Mock()
    pipeline.status.return_value = PipelineStatus.STOPPED
    with (
        patch("dbt.adapters.feldera.isolated_unit.PipelineBuilder") as builder,
        patch("dbt.adapters.feldera.isolated_unit.Pipeline.get", return_value=pipeline),
    ):

        def fail_create(*args, **kwargs):
            pipeline.description.return_value = builder.call_args.kwargs["description"] if owned else "someone else"
            raise RuntimeError("create response lost")

        builder.return_value.create.side_effect = fail_create
        with pytest.raises(RuntimeError, match="create response lost"):
            run_unit_test(Mock(), "select 1 as id", [], "", None, "", "", "", 30)
    if owned:
        pipeline.delete.assert_called_once_with(clear_storage=True)
    else:
        pipeline.delete.assert_not_called()


@pytest.mark.parametrize(("command", "expected"), [("test", 1), ("test", 2), ("build", 2)])
@pytest.mark.parametrize("safe_seed_reuse", [False, True])
def test_unit_only_invocation_does_not_finalize_workload(tmp_path, monkeypatch, command, expected, safe_seed_reuse):
    (tmp_path / "models").mkdir()
    (tmp_path / "dbt_project.yml").write_text(
        "name: isolation_test\nversion: '1.0'\nconfig-version: 2\nprofile: isolation_test\n"
        f"vars:\n  feldera_safe_seed_reuse: {str(safe_seed_reuse).lower()}\n"
    )
    (tmp_path / "profiles.yml").write_text(
        "isolation_test:\n  target: dev\n  outputs:\n    dev:\n"
        "      type: feldera\n      host: http://localhost:8080\n"
        "      database: feldera\n      schema: example\n      threads: 1\n"
    )
    (tmp_path / "models" / "sample.sql").write_text("select 1 as id")
    (tmp_path / "models" / "schema.yml").write_text(
        "version: 2\nunit_tests:\n  - name: sample_unit\n    model: sample\n"
        "    given: []\n    expect:\n      rows: [{id: 1}]\n"
    )
    monkeypatch.chdir(tmp_path)
    table = agate.Table([(1, "actual"), (expected, "expected")], ["id", "actual_or_expected"])
    with (
        patch.object(FelderaAdapter, "list_schemas", return_value=[]),
        patch.object(FelderaAdapter, "list_relations_without_caching", return_value=[]),
        patch.object(FelderaAdapter, "create_schema"),
        patch.object(FelderaAdapter, "run_isolated_unit_test", lambda *args: (AdapterResponse(_message="OK"), table)),
        patch.object(FelderaAdapter, "finalize_seeds") as finalize,
    ):
        result = dbtRunner().invoke(
            [command, "--profiles-dir", str(tmp_path), "--select", "sample", "--no-partial-parse"]
        )
        assert result.success == (expected == 1), str(result.exception)
        assert any(entry.node.resource_type == "unit_test" for entry in result.result.results)
        assert all(
            entry.status == "success" for entry in result.result.results if entry.node.resource_type == "operation"
        )
        finalize.assert_not_called()
