package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.CompilerOptions;
import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.junit.Test;

/** Numeric semantics of Spark 3.5 with spark.sql.ansi.enabled=false, enabled by --sparkCompat. */
public class SparkNumericTests extends SqlIoTest {
    @Override
    public CompilerOptions testOptions() {
        CompilerOptions options = super.testOptions();
        options.languageOptions.sparkCompat = true;
        return options;
    }

    @Test
    public void decimalDivisionAndMultiplication() {
        var ccs = this.getCCS("""
                CREATE TABLE T(a DECIMAL(38,2), b DECIMAL(38,2), c DECIMAL(10,2), d DECIMAL(10,2),
                               e DECIMAL(36,4), f DECIMAL(38,10), g DECIMAL(38,10));
                CREATE VIEW V AS SELECT a / b AS q, c / d AS r, e / 7 AS s, f * g AS p FROM T;""");
        ccs.stepWeightOne("INSERT INTO T VALUES(1, 3, 2, 3, 1, 0.0000005, 1)", """
                 q | r | s | p
                ---------------
                 0.333333 | 0.6666666666667 | 0.142857 | 0.000001""");
    }

    @Test
    public void roundDouble() {
        this.qs("""
                SELECT ROUND(x, 2) AS a, ROUND(-x, 2) AS b, ROUND(y, 2) AS c, ROUND(z, 2) AS d
                FROM (VALUES (CAST(0.145 AS DOUBLE), CAST(1.005 AS DOUBLE), CAST(2.62e20 AS DOUBLE))) t(x, y, z);
                 a | b | c | d
                ---------------
                 0.15 | -0.15 | 1.01 | 2.62e20
                (1 row)""");
    }

    @Test
    public void decimalCastOverflowIsNull() {
        this.qs("""
                SELECT CAST(x AS DECIMAL(38,18)) IS NULL AS a, CAST(s AS DECIMAL(38,18)) IS NULL AS b
                FROM (VALUES (CAST(1e20 AS DOUBLE), '1e30')) t(x, s);
                 a | b
                -------
                 true | true
                (1 row)""");
    }

    @Test
    public void sumAndAvgOfIntegers() {
        this.qs("""
                SELECT SUM(x) AS s, AVG(x) AS a FROM (VALUES (2147483647), (2)) t(x);
                 s | a
                -------
                 2147483649 | 1073741824.5
                (1 row)""");
    }

    @Test
    public void timestampToString() {
        this.qst("""
                SELECT CAST(a AS VARCHAR) AS a, CAST(b AS VARCHAR) AS b, CAST(c AS VARCHAR) AS c
                FROM (VALUES (TIMESTAMP '2024-01-01 10:00:00.12', TIMESTAMP '2024-01-01 10:00:00',
                              TIMESTAMP '2024-01-01 10:00:00.123456')) t(a, b, c);
                 a | b | c
                -----------
                 2024-01-01 10:00:00.12 | 2024-01-01 10:00:00 | 2024-01-01 10:00:00.123456
                (1 row)""");
    }

    @Test
    public void decimalToDouble() {
        this.qs("""
                SELECT CAST(x AS DOUBLE) AS d FROM (VALUES (CAST(129023.45 AS DECIMAL(18,2)))) t(x);
                 d
                ---
                 129023.45
                (1 row)""");
    }
}
