import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import agate
import pyarrow as pa

from dbt.adapters.feldera.connections import FelderaConnectionManager
from dbt.adapters.feldera.impl import FelderaAdapter


class TestSafeSeedReuse(unittest.TestCase):
    def test_row_multiset_preserves_duplicates_and_quotes_table_names(self):
        batch = pa.record_batch([pa.array(["a", "a", None])], names=["CODE"])
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        expected = [{"code": "a"}, {"code": None}, {"code": "a"}]
        self.assertTrue(FelderaAdapter._seed_rows_present(pipeline, 'seed"name', expected))
        pipeline.query_arrow.assert_called_once_with('SELECT * FROM "seed""name"')

        pipeline.query_arrow.return_value = iter([batch])
        with self.assertRaisesRegex(RuntimeError, "cold rebuild"):
            FelderaAdapter._seed_rows_present(pipeline, "seed", expected[:-1])

    def test_timestamp_zero_precision_beyond_microseconds_is_accepted(self):
        batch = pa.record_batch(
            [pa.array([datetime(2013, 9, 8)], type=pa.timestamp("us"))],
            names=["created_at"],
        )
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        expected = [{"created_at": "2013-09-08 00:00:00.0000000"}]
        self.assertTrue(FelderaAdapter._seed_rows_present(pipeline, "seed", expected))

    def test_timestamp_normalization_preserves_timezone_offset(self):
        batch = pa.record_batch(
            [pa.array([datetime(2013, 9, 7, 22, tzinfo=timezone.utc)], type=pa.timestamp("us", tz="UTC"))],
            names=["created_at"],
        )
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        expected = [{"created_at": "2013-09-08 00:00:00.0000000+02:00"}]
        self.assertTrue(FelderaAdapter._seed_rows_present(pipeline, "seed", expected))

    def test_nonzero_excess_timestamp_precision_is_rejected(self):
        batch = pa.record_batch(
            [pa.array([datetime(2013, 9, 8)], type=pa.timestamp("us"))],
            names=["created_at"],
        )
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        expected = [{"created_at": "2013-09-08 00:00:00.0000001"}]
        with self.assertRaisesRegex(ValueError, "exceeds us precision"):
            FelderaAdapter._seed_rows_present(pipeline, "seed", expected)

    def test_timestamp_like_string_values_are_not_normalized(self):
        value = "2013-09-08 00:00:00.0000000"
        batch = pa.record_batch([pa.array([value])], names=["value"])
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        self.assertTrue(FelderaAdapter._seed_rows_present(pipeline, "seed", [{"value": value}]))

    def test_empty_existing_storage_requests_seed_submission(self):
        batch = pa.record_batch([pa.array([], type=pa.int64())], names=["id"])
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        self.assertFalse(FelderaAdapter._seed_rows_present(pipeline, "seed", [{"id": 1}]))

    def test_finalizer_reuses_matching_rows_and_reingests_empty_storage(self):
        populated = pa.record_batch([pa.array([1], type=pa.int64())], names=["id"])
        for stored_values, should_push in (([], True), ([1], False)):
            with self.subTest(stored_values=stored_values):
                stored = pa.record_batch([pa.array(stored_values, type=pa.int64())], names=["id"])
                pipeline = Mock()
                pipeline.query_arrow.side_effect = [iter([stored]), iter([populated])]
                client = Mock()
                adapter = Mock()
                adapter._get_client.return_value = client
                adapter._get_credentials.return_value = SimpleNamespace(
                    compilation_profile="dev", spark_compat=False, workers=1, timeout=30, runtime_config={}
                )
                adapter._read_udfs.return_value = ("", "", "")
                adapter._convert_agate_rows.return_value = [{"id": 1}]
                adapter._seed_rows_present.side_effect = FelderaAdapter._seed_rows_present
                adapter._verify_seed_rows.side_effect = FelderaAdapter._verify_seed_rows

                with (
                    patch("dbt.adapters.feldera.impl.Pipeline.get", return_value=pipeline),
                    patch(
                        "dbt.adapters.feldera.impl._pipeline_state.get_pending_seed_pipelines",
                        return_value=["pipe"],
                    ),
                    patch(
                        "dbt.adapters.feldera.impl._pipeline_state.pop_pending_seeds",
                        return_value=[("seed", None, {})],
                    ),
                    patch("dbt.adapters.feldera.impl._pipeline_state.deploy", return_value=pipeline) as deploy,
                    patch("dbt.adapters.feldera.impl._pipeline_state.get_pipelines_with_views", return_value=[]),
                ):
                    FelderaAdapter.finalize_seeds(adapter, safe_seed_reuse=True)

                self.assertFalse(deploy.call_args.kwargs["replace"])
                self.assertTrue(deploy.call_args.kwargs["require_existing"])
                self.assertEqual(adapter.push_seed_data.call_count, int(should_push))
                if should_push:
                    adapter.push_seed_data.assert_called_once_with("pipe", "seed", [{"id": 1}])
                adapter._verify_seed_rows.assert_called_once_with(pipeline, "seed", [{"id": 1}])

    def test_safe_finalize_macro_refuses_failed_nodes(self):
        from jinja2 import Environment

        macro_path = Path(__file__).parents[2] / "dbt/include/feldera/macros/materializations/seed.sql"
        source = macro_path.read_text(encoding="utf-8")
        adapter = Mock()

        def raise_compiler_error(message):
            raise RuntimeError(message)

        env = Environment(extensions=["jinja2.ext.do"])
        env.globals.update(
            results=[
                SimpleNamespace(node=SimpleNamespace(resource_type="seed"), status="error"),
                SimpleNamespace(node=SimpleNamespace(resource_type="model"), status="success"),
            ],
            adapter=adapter,
            var=lambda name, default=None: name == "feldera_safe_seed_reuse" or default,
            exceptions=SimpleNamespace(raise_compiler_error=raise_compiler_error),
        )
        macro = env.from_string(source).make_module().feldera_finalize_run
        with self.assertRaisesRegex(RuntimeError, "failed or skipped node"):
            macro()
        adapter.finalize_seeds.assert_not_called()

    def test_seed_macro_materializes_only_when_safe_reuse_is_enabled(self):
        from jinja2 import Environment

        macro_path = Path(__file__).parents[2] / "dbt/include/feldera/macros/materializations/seed.sql"
        source = macro_path.read_text(encoding="utf-8")
        for enabled in (False, True):
            adapter = Mock()
            adapter.convert_type.return_value = "BIGINT"
            adapter.quote_seed_column.side_effect = lambda name, _: f'"{name}"'
            adapter.register_table.return_value = ""
            adapter.stash_seed.return_value = ""
            model = {
                "schema": "pipe",
                "name": "seed",
                "config": {"column_types": {}, "quote_columns": None},
            }
            table = SimpleNamespace(column_names=["id"])
            env = Environment(extensions=["jinja2.ext.do"])
            env.globals.update(
                adapter=adapter,
                var=lambda name, default=None: enabled if name == "feldera_safe_seed_reuse" else default,
                **{"return": lambda _: ""},
            )
            macro = env.from_string(source).make_module().feldera__create_csv_table
            macro(model, table)
            sql = adapter.register_table.call_args.args[2]
            self.assertEqual("WITH ('materialized' = 'true')" in sql, enabled)
            self.assertEqual(adapter.stash_seed.call_count, int(enabled))


class TestResultTable(unittest.TestCase):
    def test_dbt_test_counts_are_numeric(self):
        cursor = Mock()
        cursor.description = [("failures",), ("should_warn",), ("should_error",)]
        for failures in (0, 1):
            with self.subTest(failures=failures):
                cursor.fetchall.return_value = [(failures, False, failures > 0)]
                table = FelderaConnectionManager._get_result_table(cursor)
                self.assertIsInstance(table.column_types[0], agate.Number)
                self.assertIsInstance(table.column_types[1], agate.Boolean)
                self.assertIsInstance(table.column_types[2], agate.Boolean)
                self.assertEqual(table.rows[0][0], failures)
                self.assertEqual(table.rows[0][2], failures > 0)


class TestFelderaAdapterStaticMethods(unittest.TestCase):
    """Unit tests for FelderaAdapter static/class methods."""

    def test_date_function(self):
        self.assertEqual(FelderaAdapter.date_function(), "NOW()")

    def test_is_cancelable(self):
        self.assertFalse(FelderaAdapter.is_cancelable())

    def test_convert_text_type(self):
        self.assertEqual(FelderaAdapter.convert_text_type(None, 0), "VARCHAR")

    def test_convert_boolean_type(self):
        self.assertEqual(FelderaAdapter.convert_boolean_type(None, 0), "BOOLEAN")

    def test_convert_datetime_type(self):
        self.assertEqual(FelderaAdapter.convert_datetime_type(None, 0), "TIMESTAMP")

    def test_convert_date_type(self):
        self.assertEqual(FelderaAdapter.convert_date_type(None, 0), "DATE")

    def test_convert_time_type(self):
        self.assertEqual(FelderaAdapter.convert_time_type(None, 0), "TIME")


class TestFelderaAdapterPluginRegistration(unittest.TestCase):
    """Test that the adapter plugin registers correctly."""

    def test_plugin_import(self):
        from dbt.adapters.feldera import Plugin

        self.assertIsNotNone(Plugin)
        self.assertEqual(Plugin.adapter, FelderaAdapter)

    def test_plugin_credentials(self):
        from dbt.adapters.feldera import Plugin
        from dbt.adapters.feldera.credentials import FelderaCredentials

        self.assertEqual(Plugin.credentials, FelderaCredentials)

    def test_plugin_include_path_exists(self):
        import os

        from dbt.adapters.feldera import Plugin

        self.assertTrue(os.path.isdir(Plugin.include_path))

    def test_dbt_project_yml_exists(self):
        import os

        from dbt.adapters.feldera import Plugin

        dbt_project = os.path.join(Plugin.include_path, "dbt_project.yml")
        self.assertTrue(os.path.isfile(dbt_project))


class TestConvertAgateRows(unittest.TestCase):
    """Unit tests for the Python to JSON conversion in _convert_agate_rows.

    This tests the seed data conversion pipeline:
    agate Table → Python types (Decimal, datetime, bool, str) →
    JSON-safe dicts → Feldera HTTP ingress.

    Inferred and overridden SQL types determine the JSON representation.
    """

    def test_explicit_boolean_strings_follow_spark_casts(self):
        values = ["true", "t", "yes", "y", "1", "false", "f", "no", "n", "0", "", "invalid", None]
        table = agate.Table(
            [(f"  {value.upper()}  " if value else value,) for value in values],
            ["flag"],
            [agate.Text()],
        )
        self.assertEqual(
            FelderaAdapter._convert_agate_rows(table, {"flag": "BOOLEAN"}),
            [{"flag": value} for value in [True] * 5 + [False] * 5 + [None] * 3],
        )
        booleans = agate.Table([(True,), (False,), (None,)], ["flag"], [agate.Boolean()])
        self.assertEqual(
            FelderaAdapter._convert_agate_rows(booleans, {"flag": "BOOLEAN"}),
            [{"flag": True}, {"flag": False}, {"flag": None}],
        )

    def _make_table(self, rows, column_names, column_types=None):
        import agate

        if column_types is None:
            column_types = [agate.data_types.Number()] * len(column_names)
        return agate.Table(rows=rows, column_names=column_names, column_types=column_types)

    def test_nan_decimal_becomes_none(self):
        """Decimal("NaN") → None because JSON doesn't support NaN.

        Python's Decimal type supports NaN/Infinity but Feldera's DECIMAL
        does not. The converter maps these to None (SQL NULL).
        """
        from decimal import Decimal

        table = self._make_table([(Decimal("NaN"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        self.assertIsNone(result[0]["value"])

    def test_infinity_decimal_becomes_none(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("Infinity"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        self.assertIsNone(result[0]["value"])

    def test_negative_infinity_decimal_becomes_none(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("-Infinity"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        self.assertIsNone(result[0]["value"])

    def test_normal_decimal_integer_becomes_int(self):
        """Decimal("42") → int(42).

        The seed pipeline does not inspect individual values to decide
        between INT and BIGINT — all integers map to the same Python
        ``int`` (unbounded).  The SQL type for the CREATE TABLE DDL is
        chosen separately by ``convert_number_type`` (always BIGINT for
        safety).  Users who need a narrower type should use the
        ``column_types`` seed config override.
        """
        from decimal import Decimal

        table = self._make_table([(Decimal("42"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        self.assertEqual(result[0]["value"], 42)
        self.assertIsInstance(result[0]["value"], int)

    def test_inferred_double_uses_json_numbers_and_preserves_nulls(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("3.14"),), (Decimal("42"),), (None,)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        self.assertEqual(result, [{"value": 3.14}, {"value": 42.0}, {"value": None}])
        self.assertIsInstance(result[0]["value"], float)

    def test_nan_with_double_caster_becomes_none(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("NaN"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table, column_types={"value": "DOUBLE"})
        self.assertIsNone(result[0]["value"])

    def test_infinity_with_real_caster_becomes_none(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("Infinity"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table, column_types={"value": "REAL"})
        self.assertIsNone(result[0]["value"])

    def test_explicit_types_choose_correct_json_values(self):
        from decimal import Decimal

        table = self._make_table(
            [(Decimal("0.1"), Decimal("0.1"), Decimal("3"), Decimal("0.1"))],
            ["fixed", "floating", "integer", "text"],
        )
        result = FelderaAdapter._convert_agate_rows(
            table, {"fixed": "DECIMAL(20, 6)", "floating": "REAL", "integer": "BIGINT", "text": "VARCHAR"}
        )
        self.assertEqual(result, [{"fixed": "0.1", "floating": 0.1, "integer": 3, "text": "0.1"}])

    def test_unrepresentable_double_is_rejected(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("1e1000"),)], ["value"])
        with self.assertRaisesRegex(ValueError, "Non-finite floating point"):
            FelderaAdapter._convert_agate_rows(table, {"value": "DOUBLE"})

    def test_decimal_type_preserves_exact_value(self):
        """DECIMAL column_types override uses string for exact representation."""
        from decimal import Decimal

        table = self._make_table([(Decimal("0.1"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table, column_types={"value": "DECIMAL"})
        self.assertEqual(result[0]["value"], "0.1")

    def test_large_decimal_integer_becomes_int(self):
        """A large Decimal that has no fractional part becomes int."""
        from decimal import Decimal

        table = self._make_table([(Decimal("9999999999999"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        self.assertEqual(result[0]["value"], 9999999999999)
        self.assertIsInstance(result[0]["value"], int)


if __name__ == "__main__":
    unittest.main()
