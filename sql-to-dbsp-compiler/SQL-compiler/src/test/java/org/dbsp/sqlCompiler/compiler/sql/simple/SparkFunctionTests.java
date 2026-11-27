package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.sql.tools.BaseSQLTests;
import org.junit.Test;

import java.util.Map;

public class SparkFunctionTests extends BaseSQLTests {
    @Test
    public void aggregates() {
        var ccs = this.getCCS("""
                CREATE TABLE t(k INT, v VARCHAR, o INT, b BOOLEAN);
                CREATE VIEW agg AS SELECT k, COALESCE(MAX_BY(v, o), 'none') AS mx, MIN_BY(v, o) AS mn,
                    SORT_ARRAY(COLLECT_LIST(v)) AS l, SORT_ARRAY(COLLECT_SET(v)) AS s, COUNT_IF(b) AS c
                FROM t GROUP BY k;
                """).withStringTrim();
        ccs.stepMultiView("""
                INSERT INTO t VALUES (1, 'a', 3, TRUE), (1, 'b', NULL, FALSE), (1, NULL, 5, NULL), (1, 'a', 1, TRUE);
                """, Map.of("agg", """
                 k | mx   | mn | l         | s      | c | weight
                ------------------------------------------------
                 1 | none | a  | { a, a, b } | { a, b } | 2 | 1"""));
    }

    @Test
    public void dates() {
        var ccs = this.getCCS("""
                CREATE TABLE t(d DATE, ts TIMESTAMP, s VARCHAR);
                CREATE VIEW arith AS SELECT DATE_ADD(d, 1) AS a, DATE_SUB(d, 31) AS b,
                    DATE_SUB(d, INTERVAL '29' DAY) AS c, ADD_MONTHS(d, 1) AS e, LAST_DAY(ts) AS f,
                    DATEDIFF(ts, d) AS g, DAY(d) AS h, WEEKOFYEAR(d) AS i, DATE_PART('year', ts) AS j FROM t;
                CREATE VIEW months AS SELECT MONTHS_BETWEEN(ts, d) AS m, MONTHS_BETWEEN(d, LAST_DAY(ts)) AS n FROM t;
                CREATE VIEW trunc AS SELECT DATE_TRUNC('MM', ts) AS a, DATE_TRUNC('quarter', ts) AS b,
                    DATE_TRUNC('week', ts) AS c FROM t;
                CREATE VIEW fmt AS SELECT DATE_FORMAT(ts, 'yyyy/MM/dd HH:mm') AS a, DATE_FORMAT(d, 'd MMM yyyy') AS b,
                    TO_DATE(s) AS c, TO_DATE('15/03/2024', 'dd/MM/yyyy') AS e, TO_DATE('bad') AS f,
                    TO_TIMESTAMP('2024-03-15 10:30:45', 'yyyy-MM-dd HH:mm:ss') AS g, UNIX_TIMESTAMP(ts) AS h FROM t;
                """).withStringTrim();
        ccs.stepMultiView("""
                INSERT INTO t VALUES (DATE '2024-01-31', TIMESTAMP '2024-03-15 10:30:45', '2024-02-29');
                """, Map.of("arith", """
                 a          | b          | c          | e          | f          | g  | h  | i | j    | weight
                ---------------------------------------------------------------------------------------------
                 2024-02-01 | 2023-12-31 | 2024-01-02 | 2024-02-29 | 2024-03-31 | 44 | 31 | 5 | 2024 | 1""",
                "months", """
                 m          | n  | weight
                -------------------------
                 1.49800067 | -2 | 1""",
                "trunc", """
                 a                   | b                   | c                   | weight
                ---------------------------------------------------------------------------
                 2024-03-01 00:00:00 | 2024-01-01 00:00:00 | 2024-03-11 00:00:00 | 1""",
                "fmt", """
                 a                | b           | c          | e          | f    | g                   | h          | weight
                -----------------------------------------------------------------------------------------------------------
                 2024/03/15 10:30 | 31 Jan 2024 | 2024-02-29 | 2024-03-15 | NULL | 2024-03-15 10:30:45 | 1710498645 | 1"""));
    }

    @Test
    public void arraysAndStrings() {
        var ccs = this.getCCS("""
                CREATE FUNCTION f2(s VARCHAR, p VARCHAR) RETURNS VARCHAR AS s || '[' || p || ']';
                CREATE TABLE t(a INT ARRAY, n INT ARRAY, m MAP<VARCHAR, INT>, u VARCHAR, b BOOLEAN);
                CREATE VIEW arr AS SELECT ELEMENT_AT(a, 2) AS x, ELEMENT_AT(a, -1) AS y, ELEMENT_AT(a, 5) AS z,
                    ELEMENT_AT(m, 'k') AS w, SIZE(a) AS s, SIZE(n) AS sn, CAST(b AS INT) AS i FROM t;
                CREATE VIEW str AS SELECT INSTR(u, 'a') AS i, SUBSTRING_INDEX(u, '.', 2) AS p,
                    SUBSTRING_INDEX(u, '.', -2) AS q, SUBSTRING_INDEX(u, '.', 0) AS r, f2(u, 'a ') AS f FROM t;
                """).withStringTrim();
        ccs.stepMultiView("""
                INSERT INTO t VALUES (ARRAY[1, 2, 3], NULL, MAP['k', 7], 'www.apache.org', TRUE);
                """, Map.of("arr", """
                 x | y | z    | w | s | sn | i | weight
                ----------------------------------------
                 2 | 3 | NULL | 7 | 3 | -1 | 1 | 1""",
                "str", """
                 i | p          | q          | r | f                  | weight
                ---------------------------------------------------------------
                 5 | www.apache | apache.org |   | www.apache.org[a ] | 1"""));
    }
}
