from unittest.mock import patch

import agate
import pytest

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.connections import FelderaConnectionManager
from dbt.adapters.feldera.impl import FelderaAdapter
from dbt.adapters.feldera.pipeline_manager import PipelineStateManager
from dbt.cli.main import dbtRunner


@pytest.mark.parametrize("quoted", [True, False])
def test_versioned_view_alias_matches_refs_and_tests(tmp_path, monkeypatch, quoted):
    (tmp_path / "models").mkdir()
    (tmp_path / "macros").mkdir()
    (tmp_path / "dbt_project.yml").write_text(
        "name: alias_test\nversion: '1.0'\nconfig-version: 2\nprofile: alias_test\n"
        f"quoting:\n  identifier: {str(quoted).lower()}\n"
    )
    (tmp_path / "profiles.yml").write_text(
        "alias_test:\n  target: dev\n  outputs:\n    dev:\n"
        "      type: feldera\n      host: http://localhost:8080\n"
        "      database: feldera\n      schema: example\n      threads: 1\n"
    )
    (tmp_path / "macros" / "connectors.sql").write_text(
        "{% macro feldera__default_view_connectors(model_name, schema) %}\n"
        "{{ return([{'transport': {'name': schema ~ '_' ~ model_name}}]) }}\n"
        "{% endmacro %}\n"
    )
    (tmp_path / "models" / "versioned_v1.sql").write_text("{{ config(alias='AliasView', stored=true) }} select 1 as id")
    (tmp_path / "models" / "plain.sql").write_text("{{ config(stored=true, connectors=[]) }} select 1 as id")
    (tmp_path / "models" / "input.sql").write_text(
        "{{ config(materialized='table', alias='InputAlias', "
        "connectors=[{'transport': {'name': 'input_connector'}}]) }} id BIGINT"
    )
    (tmp_path / "models" / "dependent.sql").write_text(
        "{{ config(connectors=[{'transport': {'name': 'explicit'}}]) }} "
        "select a.id from {{ ref('versioned', version=1) }} a "
        "join {{ ref('input') }} b on a.id = b.id"
    )
    (tmp_path / "models" / "schema.yml").write_text(
        "version: 2\nmodels:\n  - name: versioned\n    latest_version: 1\n"
        "    versions:\n      - v: 1\n"
        "    columns:\n      - name: id\n        data_tests: [not_null]\n"
        "  - name: plain\n    columns:\n      - name: id\n        data_tests: [not_null]\n"
    )
    monkeypatch.chdir(tmp_path)
    state = PipelineStateManager()
    queries = []

    def execute(self, sql, auto_begin=False, fetch=False, limit=None):
        queries.append(sql)
        if fetch:
            return AdapterResponse(_message="OK"), agate.Table(
                [(0, False, False)],
                ["failures", "should_warn", "should_error"],
                [agate.Number(), agate.Boolean(), agate.Boolean()],
            )
        return AdapterResponse(_message="OK"), agate.Table([], [])

    with (
        patch("dbt.adapters.feldera.impl._pipeline_state", state),
        patch.object(FelderaAdapter, "list_schemas", return_value=[]),
        patch.object(FelderaAdapter, "list_relations_without_caching", return_value=[]),
        patch.object(FelderaAdapter, "create_schema"),
        patch.object(FelderaAdapter, "finalize_seeds", lambda *args: ""),
        patch.object(FelderaConnectionManager, "execute", execute),
    ):
        for _ in range(2):
            state.remove_table_if_exists("example", "InputAlias")
            queries.clear()
            result = dbtRunner().invoke(["build", "--profiles-dir", str(tmp_path), "--no-partial-parse"])
            assert result.success, str(result.exception)
            views = state.get_views("example")
            alias = '"AliasView"' if quoted else "AliasView"
            plain = '"plain"' if quoted else "plain"
            input_name = '"InputAlias"' if quoted else "InputAlias"
            assert set(views) == {"AliasView", "plain", "dependent"}
            assert f"VIEW {alias}" in views["AliasView"]
            assert "example_AliasView" in views["AliasView"]
            assert f"from {alias}" in views["dependent"]
            assert f"join {input_name}" in views["dependent"]
            assert "explicit" in views["dependent"]
            assert "connectors" not in views["plain"]
            table = state.get_tables("example")["InputAlias"]
            assert f"CREATE TABLE {input_name}" in table
            assert "input_connector" in table
            assert "example_InputAlias" not in table
            assert any(f"from {alias}" in sql for sql in queries)
            assert any(f"from {plain}" in sql for sql in queries)
