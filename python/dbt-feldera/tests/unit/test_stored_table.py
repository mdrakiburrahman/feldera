from unittest.mock import patch

import agate

from dbt.adapters.contracts.connection import AdapterResponse
from dbt.adapters.feldera.connections import FelderaConnectionManager
from dbt.adapters.feldera.impl import FelderaAdapter
from dbt.cli.main import dbtRunner


def test_stored_tables_and_seeds_materialize_without_safe_seed_reuse(tmp_path, monkeypatch):
    (tmp_path / "models").mkdir()
    (tmp_path / "seeds").mkdir()
    (tmp_path / "dbt_project.yml").write_text(
        "name: stored_test\nversion: '1.0'\nconfig-version: 2\nprofile: stored_test\n"
        "vars:\n  feldera_safe_seed_reuse: false\n"
        "seeds:\n  stored_test:\n    +stored: true\n"
    )
    (tmp_path / "profiles.yml").write_text(
        "stored_test:\n  target: dev\n  outputs:\n    dev:\n"
        "      type: feldera\n      host: http://localhost:8080\n"
        "      database: feldera\n      schema: example\n      threads: 1\n"
    )
    (tmp_path / "models" / "source.sql").write_text(
        "{{ config(materialized='table', stored=true, "
        "connectors=[{'transport': {'name': 'input'}}]) }} id BIGINT"
    )
    (tmp_path / "seeds" / "seeded.csv").write_text("id\n1\n")
    monkeypatch.chdir(tmp_path)
    table_sql = {}

    def register_table(self, pipeline, name, sql):
        table_sql[name] = sql
        return ""

    with (
        patch.object(FelderaAdapter, "list_schemas", return_value=[]),
        patch.object(FelderaAdapter, "list_relations_without_caching", return_value=[]),
        patch.object(FelderaAdapter, "create_schema"),
        patch.object(FelderaAdapter, "register_table", register_table),
        patch.object(FelderaAdapter, "finalize_seeds", lambda *args: ""),
        patch.object(
            FelderaConnectionManager,
            "execute",
            return_value=(AdapterResponse(_message="OK"), agate.Table([], [])),
        ),
    ):
        result = dbtRunner().invoke(["build", "--profiles-dir", str(tmp_path), "--no-partial-parse"])

    assert result.success, str(result.exception)
    assert set(table_sql) == {"source", "seeded"}
    assert "WITH ('materialized' = 'true', 'connectors' = '" in table_sql["source"]
    assert table_sql["source"].count("WITH (") == 1
    assert "WITH ('materialized' = 'true')" in table_sql["seeded"]
