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
        options.languageOptions.incrementalize = true;
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
    @Test
    public void integralDivisionAndOverflow() {
        var ccs = this.getCCS("""
                CREATE TABLE T(a BIGINT NOT NULL, b BIGINT NOT NULL, i INT NOT NULL);
                CREATE VIEW V AS SELECT a/b AS q, a/b IS NULL AS n, i+1 AS s, i*2 AS p FROM T;""");
        ccs.stepWeightOne("INSERT INTO T VALUES (1,2,2147483647),(-1,2,2147483647),(1,0,1)", """
                 q | n | s | p
                ---------------
                 0.5 | false | -2147483648 | -2
                 -0.5 | false | -2147483648 | -2
                 NULL | true | 2 | 2""");
        this.qs("""
                SELECT 1/2 AS q, 1/0 AS n;
                 q | n
                -------
                 0.5 | NULL
                (1 row)""");
    }

    @Test
    public void decimalArithmeticNullability() {
        var ccs = this.getCCS("""
                CREATE TABLE T(a DECIMAL(38,0) NOT NULL, b DECIMAL(38,0) NOT NULL);
                CREATE VIEW V AS SELECT a+b AS s, a-b AS d, a*b AS p, a/b AS q, a/b IS NULL AS n FROM T;""");
        ccs.stepWeightOne("""
                INSERT INTO T VALUES (99999999999999999999999999999999999999,1),
                                     (1,0),(-99999999999999999999999999999999999999,2)""", """
                 s | d | p | q | n
                -----------------
                 NULL | 99999999999999999999999999999999999998 | 99999999999999999999999999999999999999 | NULL | true
                 1 | 1 | 0 | NULL | true
                 -99999999999999999999999999999999999997 | NULL | NULL | NULL | true""");
    }

    @Test
    public void decimalAddSubtractHalfUp() {
        var ccs = this.getCCS("""
                CREATE TABLE T(a DECIMAL(38,10) NOT NULL, b DECIMAL(38,10) NOT NULL);
                CREATE VIEW V AS SELECT a+b AS s, a-b AS d FROM T;""");
        ccs.stepWeightOne("""
                INSERT INTO T VALUES (0.0000000005,0),(-0.0000000005,0),
                                     (0.0000000006,0.0000000004)""", """
                 s | d
                -------
                 0.000000001 | 0.000000001
                 -0.000000001 | -0.000000001
                 0.000000001 | 0""");
    }

    @Test
    public void stringToIntegerNonAnsi() {
        var ccs = this.getCCS("""
                CREATE TABLE T(s VARCHAR NOT NULL);
                CREATE VIEW V AS SELECT CAST(s AS TINYINT) AS b, CAST(s AS SMALLINT) AS s,
                  CAST(s AS INT) AS i, CAST(s AS BIGINT) AS l, CAST(s AS INT) IS NULL AS n FROM T;""");
        ccs.stepWeightOne("""
                INSERT INTO T VALUES ('1.9'),('-1.9'),('.'),('  +12  '),('1e2'),
                  ('128'),('32768'),('2147483648'),('9223372036854775808'),
                  ('9223372036854775807.9'),('-2147483648.9')""", """
                 b | s | i | l | n
                -------------------
                 1 | 1 | 1 | 1 | false
                 -1 | -1 | -1 | -1 | false
                 0 | 0 | 0 | 0 | false
                 12 | 12 | 12 | 12 | false
                 NULL | NULL | NULL | NULL | true
                 NULL | 128 | 128 | 128 | false
                 NULL | NULL | 32768 | 32768 | false
                 NULL | NULL | NULL | 2147483648 | true
                 NULL | NULL | NULL | NULL | true
                 NULL | NULL | NULL | 9223372036854775807 | true
                 NULL | NULL | -2147483648 | -2147483648 | false""");
        this.qs("""
                SELECT CAST('1.9' AS INT) AS i, CAST('1e2' AS INT) AS n,
                       SAFE_CAST('1.9' AS INT) AS strict;
                 i | n | strict
                ----------------
                 1 | NULL | NULL
                (1 row)""");
    }

    @Test
    public void decimalAverageSingleRounding() {
        var ccs = this.getCCS("""
                CREATE TABLE T(g INT, a DECIMAL(10,2), b DECIMAL(38,36), c DECIMAL(38,0));
                CREATE VIEW V AS SELECT g, AVG(a) AS a, AVG(b) AS b, AVG(c) AS c,
                  AVG(a) FILTER (WHERE a > 0) AS f, AVG(DISTINCT a) AS d FROM T GROUP BY g;""");
        ccs.stepWeightOne("""
                INSERT INTO T VALUES
                  (1,1,0.123456789012345678901234567890123456,1),
                  (1,0,0.123456789012345678901234567890123457,1),
                  (2,1,NULL,99999999999999999999999999999999999999),
                  (2,0,NULL,99999999999999999999999999999999999999),
                  (2,0,NULL,NULL),
                  (3,NULL,NULL,NULL)""", """
                 g | a | b | c | f | d
                ----------------------
                 1 | 0.5 | 0.12345678901234567890123456789012345650 | 1 | 1 | 0.5
                 2 | 0.333333 | NULL | NULL | 1 | 0.5
                 3 | NULL | NULL | NULL | NULL | NULL""");
    }


    @Test
    public void decimalAverageTiesAndRetractions() {
        var ccs = this.getCCS("""
                CREATE TABLE T(a DECIMAL(5,0));
                CREATE VIEW V AS SELECT AVG(a) AS p, AVG(-a) AS n FROM T;""");
        ccs.stepWeightOne("INSERT INTO T VALUES (1)" + ",(0)".repeat(31), """
                 p | n
                -------
                 0.0313 | -0.0313""");
        ccs.step("REMOVE FROM T VALUES (1)", """
                 p | n | weight
                ----------------
                 0.0313 | -0.0313 | -1
                 0 | 0 | 1""");
    }

    @Test
    public void decimalAverageWindow() {
        var ccs = this.getCCS("""
                CREATE TABLE T(id INT, a DECIMAL(5,0));
                CREATE VIEW V AS SELECT id,
                  AVG(a) OVER(ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS a FROM T;""");
        ccs.stepWeightOne("INSERT INTO T VALUES (1,1),(2,2),(3,3)", """
                 id | a
                --------
                 1 | 1
                 2 | 1.5
                 3 | 2.5""");
    }

    @Test
    public void fullScaleDecimalArithmetic() {
        this.qs("""
                SELECT CAST(.5 AS DECIMAL(38,38))*CAST(.5 AS DECIMAL(38,38)) AS p,
                       CAST(1 AS DECIMAL(38,0)) % CAST(0 AS DECIMAL(38,0)) AS z;
                 p | z
                -------
                 0.25 | NULL
                (1 row)""");
    }
}
