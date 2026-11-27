package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.DBSPCompiler;
import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.junit.Test;

public class SparkPercentileTests extends SqlIoTest {
    @Override
    public void prepareInputs(DBSPCompiler compiler) {
        compiler.submitStatementsForCompilation("""
                CREATE TABLE samples(g INT NOT NULL, x INT);
                INSERT INTO samples VALUES (1, 0), (1, 20), (1, NULL),
                    (2, 0), (2, 0), (2, 2), (2, 4), (3, NULL), (4, -7);
                """);
    }

    @Test
    public void interpolation() {
        this.qst("""
                SELECT g, PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY x ASC) AS a,
                       PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY x DESC) AS d,
                       PERCENTILE(x, 0.5) AS median
                FROM samples GROUP BY g;
                 g | a    | d    | median
                -------------------------
                 1 | 5    | 15   | 10
                 2 | 0    | 2.5  | 1
                 3 | NULL | NULL | NULL
                 4 | -7   | -7   | -7
                (4 rows)""");
    }

    @Test
    public void endpointsAndEmptyInput() {
        this.qst("""
                SELECT PERCENTILE_CONT(0) WITHIN GROUP (ORDER BY x DESC) AS hi,
                       PERCENTILE_CONT(1) WITHIN GROUP (ORDER BY x DESC) AS lo,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY x) FILTER (WHERE g = 3) AS n
                FROM samples;
                 hi | lo | n
                -------------
                 20 | -7 | NULL
                (1 row)

                SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY x) AS p
                FROM samples WHERE g = 5;
                 p
                ----
                 NULL
                (1 row)""");
    }
}
