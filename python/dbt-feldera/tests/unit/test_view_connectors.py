from unittest.mock import patch

import agate

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.connections import FelderaConnectionManager
from dbt.adapters.feldera.impl import FelderaAdapter
from dbt.cli.main import dbtRunner


def test_default_view_connectors_dispatch_and_overrides(tmp_path, monkeypatch):
    (tmp_path / "models").mkdir()
    (tmp_path / "macros").mkdir()
    (tmp_path / "dbt_project.yml").write_text(
        "name: hook_test\nversion: '1.0'\nconfig-version: 2\nprofile: hook_test\n"
        "dispatch:\n  - macro_namespace: dbt\n    search_order: [hook_test, dbt]\n"
    )
    (tmp_path / "profiles.yml").write_text(
        "hook_test:\n  target: dev\n  outputs:\n    dev:\n"
        "      type: feldera\n      host: http://localhost:8080\n"
        "      database: feldera\n      schema: example\n      threads: 1\n"
    )
    override = (
        "{% macro feldera__default_view_connectors(model_name, schema) %}\n"
        "  {% if model_name != 'defaulted' %}\n"
        "    {{ exceptions.raise_compiler_error('unexpected hook call') }}\n"
        "  {% endif %}\n"
        "  {{ return([{'transport': {'name': schema ~ '_' ~ model_name}}]) }}\n"
        "{% endmacro %}\n"
    )
    (tmp_path / "models" / "defaulted.sql").write_text("select 1 as id")
    (tmp_path / "models" / "explicit.sql").write_text(
        "{{ config(connectors=[{'transport': {'name': 'explicit'}}]) }} select 1 as id"
    )
    (tmp_path / "models" / "disabled.sql").write_text("{{ config(connectors=[]) }} select 1 as id")
    (tmp_path / "models" / "source.sql").write_text("{{ config(materialized='table') }} id BIGINT")
    monkeypatch.chdir(tmp_path)
    view_sql = {}
    table_sql = {}

    def register_view(self, pipeline, name, sql):
        view_sql[name] = sql
        return ""

    def register_table(self, pipeline, name, sql):
        table_sql[name] = sql
        return ""

    with (
        patch.object(FelderaAdapter, "list_schemas", return_value=[]),
        patch.object(FelderaAdapter, "list_relations_without_caching", return_value=[]),
        patch.object(FelderaAdapter, "create_schema"),
        patch.object(FelderaAdapter, "register_view", register_view),
        patch.object(FelderaAdapter, "register_table", register_table),
        patch.object(FelderaAdapter, "finalize_seeds", lambda *args: ""),
        patch.object(
            FelderaConnectionManager,
            "execute",
            return_value=(AdapterResponse(_message="OK"), agate.Table([], [])),
        ),
    ):
        result = dbtRunner().invoke(["run", "--profiles-dir", str(tmp_path), "--no-partial-parse"])
        assert result.success, str(result.exception)
        assert "connectors" not in view_sql["defaulted"]

        (tmp_path / "macros" / "connectors.sql").write_text(override)
        view_sql.clear()
        result = dbtRunner().invoke(["run", "--profiles-dir", str(tmp_path), "--no-partial-parse"])
        assert result.success, str(result.exception)
        assert "example_defaulted" in view_sql["defaulted"]
        assert "explicit" in view_sql["explicit"] and "example_explicit" not in view_sql["explicit"]
        assert "connectors" not in view_sql["disabled"]
        assert "connectors" not in table_sql["source"]
