import unittest
from unittest.mock import Mock

import agate
import pyarrow as pa

from dbt.adapters.feldera.connections import FelderaConnectionManager
from dbt.adapters.feldera.impl import FelderaAdapter


class TestSafeSeedReuse(unittest.TestCase):
    def test_comparison_preserves_duplicate_multiplicity(self):
        batch = pa.record_batch([pa.array(["a", "a", None])], names=["CODE"])
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([batch])
        expected = [{"code": "a"}, {"code": None}, {"code": "a"}]
        FelderaAdapter._verify_seed_rows(pipeline, 'seed"name', expected)
        pipeline.query_arrow.assert_called_once_with('SELECT * FROM "seed""name"')
        pipeline.query_arrow.return_value = iter([batch])
        with self.assertRaisesRegex(RuntimeError, "cold rebuild"):
            FelderaAdapter._verify_seed_rows(pipeline, "seed", expected[:-1])

    def test_nonempty_expected_rejects_empty_query(self):
        pipeline = Mock()
        pipeline.query_arrow.return_value = iter([])
        with self.assertRaisesRegex(RuntimeError, "cold rebuild"):
            FelderaAdapter._verify_seed_rows(pipeline, "seed", [{"code": "a"}])


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

    The SQL type system (DECIMAL vs DOUBLE) is a separate concern
    handled by column_types overrides.
    """

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

    def test_normal_decimal_float_becomes_string(self):
        from decimal import Decimal

        table = self._make_table([(Decimal("3.14"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table)
        # Fractional decimals are preserved as strings for exact representation
        self.assertEqual(result[0]["value"], "3.14")
        self.assertIsInstance(result[0]["value"], str)

    def test_nan_with_double_caster_becomes_string(self):
        """DOUBLE column_types override uses str(), so NaN becomes "NaN"."""
        from decimal import Decimal

        table = self._make_table([(Decimal("NaN"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table, column_types={"value": "DOUBLE"})
        self.assertEqual(result[0]["value"], "NaN")

    def test_infinity_with_real_caster_becomes_string(self):
        """REAL column_types override uses str(), so Infinity becomes "Infinity"."""
        from decimal import Decimal

        table = self._make_table([(Decimal("Infinity"),)], ["value"])
        result = FelderaAdapter._convert_agate_rows(table, column_types={"value": "REAL"})
        self.assertEqual(result[0]["value"], "Infinity")

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
