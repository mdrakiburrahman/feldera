from unittest.mock import Mock, patch

import agate
import pytest
from feldera.enums import PipelineStatus

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.impl import FelderaAdapter
from dbt.adapters.feldera.isolated_unit import _sql_type, is_isolated_unit_pipeline, run_unit_test
from dbt.adapters.feldera.pipeline_manager import set_pre_start_hook
from dbt.cli.main import dbtRunner



def test_char_output_type_preserves_width():
    assert _sql_type({"columntype": {"type": "CHAR", "precision": 5}}) == "CHAR(5)"


@pytest.mark.parametrize("precision", [None, -1, 0, "5", True])
def test_char_output_type_rejects_unknown_width(precision):
    with pytest.raises(ValueError, match="CHAR column has unknown or invalid width"):
        _sql_type({"columntype": {"type": "CHAR", "precision": precision}})

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


def test_scratch_admission_is_identity_scoped_and_cleanup_is_guaranteed():
    pipeline = Mock()
    pipeline.status.return_value = PipelineStatus.STOPPED
    pipeline.program_info.side_effect = [
        _info(["dbt_unit_actual"]),
        _info(["dbt_unit_actual", "dbt_unit_expected"]),
    ]
    pipeline.query.side_effect = [[{"id": 1}, {"id": 1}, {"id": None}], [{"id": 1}, {"id": None}]]
    forged = Mock()
    forged.name = pipeline.name
    admitted = []

    def guard(p):
        assert is_isolated_unit_pipeline(p)
        assert not is_isolated_unit_pipeline(forged)
        admitted.append(p)

    set_pre_start_hook(guard, required=True)
    try:
        with (
            patch("dbt.adapters.feldera.isolated_unit.PipelineBuilder") as builder,
            patch("dbt.adapters.feldera.isolated_unit._wait_for_sql"),
            patch("dbt.adapters.feldera.isolated_unit.PipelineStateManager._wait_for_compilation"),
        ):
            builder.return_value.create.return_value = pipeline
            _, table = run_unit_test(
                Mock(), "select 1 as id", [{"id": 1}], "", lambda *_: "select 1 as id", "", "", "", 30
            )
        assert len(table.rows) == 5
        assert admitted == [pipeline]
        assert not is_isolated_unit_pipeline(pipeline)
        assert not is_isolated_unit_pipeline(None)
        pipeline.start.assert_called_once()
        pipeline.delete.assert_called_once_with(clear_storage=True)
    finally:
        set_pre_start_hook(None)


@pytest.mark.parametrize("stage", ["connector", "rejection", "invalid_expected"])
def test_scratch_failure_never_starts_and_cleans_up(stage):
    pipeline = Mock()
    pipeline.status.return_value = PipelineStatus.STOPPED
    pipeline.program_info.side_effect = [
        _info(["dbt_unit_actual"]),
        _info(["dbt_unit_actual", "dbt_unit_expected"], {"unsafe": {}} if stage == "connector" else None),
    ]
    callback = Mock(side_effect=RuntimeError("denied") if stage == "rejection" else None)
    set_pre_start_hook(callback, required=True)
    try:
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
        assert not is_isolated_unit_pipeline(pipeline)
        if stage == "connector":
            callback.assert_not_called()
    finally:
        set_pre_start_hook(None)


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


@pytest.mark.parametrize("command", ["test", "build"])
def test_unit_only_invocation_does_not_finalize_workload(tmp_path, monkeypatch, command):
    (tmp_path / "models").mkdir()
    (tmp_path / "dbt_project.yml").write_text(
        "name: isolation_test\nversion: '1.0'\nconfig-version: 2\nprofile: isolation_test\n"
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
    expected = 1 if command == "test" else 2
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
        assert result.success == (command == "test"), str(result.exception)
        assert any(entry.node.resource_type == "unit_test" for entry in result.result.results)
        finalize.assert_not_called()
