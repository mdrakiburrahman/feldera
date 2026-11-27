package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.sql.tools.BaseSQLTests;
import org.junit.Test;

import java.util.Map;

public class SparkGeneratorTests extends BaseSQLTests {
    @Test
    public void explodeInnerAndOuter() {
        var ccs = this.getCCS("""
                CREATE TABLE entries(id INT, arr INT ARRAY);
                CREATE VIEW projected AS SELECT id, EXPLODE(arr) AS val FROM entries;
                CREATE VIEW expanded AS SELECT id, g.val FROM entries AS p LATERAL VIEW EXPLODE(p.arr) g AS val;
                CREATE VIEW padded AS SELECT id, val FROM entries p LATERAL VIEW OUTER EXPLODE(p.arr) g AS val;
                """);
        String inner = """
                 id | val  | weight
                --------------------
                 1  | 7    | 2
                 1  | NULL | 1""";
        ccs.stepMultiView("""
                INSERT INTO entries VALUES (1, ARRAY[7, 7, NULL]), (2, ARRAY()), (3, NULL);
                """, Map.of("projected", inner, "expanded", inner, "padded", inner + """

                 2  | NULL | 1
                 3  | NULL | 1"""));
    }

    @Test
    public void explodeRecordsAndMaps() {
        var ccs = this.getCCS("""
                CREATE TYPE sample AS (reading INT, seq INT);
                CREATE TABLE entries(id INT, arr sample ARRAY, labels MAP<VARCHAR, INT>);
                CREATE VIEW readings AS SELECT p.id, gen.item.seq FROM entries AS p
                    LATERAL VIEW EXPLODE(p.arr) gen AS item WHERE item.reading IS NOT NULL;
                CREATE VIEW outer_readings AS SELECT id, item.seq FROM entries p
                    LATERAL VIEW OUTER EXPLODE(p.arr) gen AS item;
                CREATE VIEW labels AS SELECT id, EXPLODE(labels) AS (label, score) FROM entries;
                CREATE VIEW outer_labels AS SELECT id, g.score FROM entries p
                    LATERAL VIEW OUTER EXPLODE(p.labels) g AS label, score;
                """).withStringTrim();
        ccs.stepMultiView("""
                INSERT INTO entries VALUES (1, ARRAY[ROW(4, 8), ROW(NULL, 9), NULL], MAP['x', 6]),
                    (2, ARRAY(), NULL), (3, NULL, NULL);
                """, Map.of("readings", """
                  id | seq | weight
                 -------------------
                  1  | 8   | 1""", "labels", """
                  id | label | score | weight
                 -----------------------------
                  1  | x     | 6     | 1""", "outer_readings", """
                  id | seq  | weight
                 --------------------
                  1  | 8    | 1
                  1  | 9    | 1
                  1  | NULL | 1
                  2  | NULL | 1
                  3  | NULL | 1""", "outer_labels", """
                  id | score | weight
                 --------------------
                  1  | 6     | 1
                  2  | NULL  | 1
                  3  | NULL  | 1"""));
    }

    @Test
    public void stackShape() {
        var ccs = this.getCCS("""
                CREATE TABLE entries(id INT, a INT, b INT, c INT);
                CREATE VIEW expanded AS SELECT r.id, s.bucket, status FROM entries r
                    LATERAL VIEW STACK(3, 'a',r.a,'b',r.b,'c',r.c) s AS bucket,status;
                CREATE VIEW partial AS SELECT id, STACK(2, 'x', a, 'y') AS (bucket, status) FROM entries;
                CREATE VIEW blanks AS SELECT id, col0 FROM entries LATERAL VIEW OUTER STACK(3, a) s;
                """).withStringTrim();
        ccs.stepMultiView("INSERT INTO entries VALUES (5, 12, NULL, 18);", Map.of(
                "expanded", """
                  id | bucket | status | weight
                 ------------------------------
                  5  | a      | 12     | 1
                  5  | b      | NULL   | 1
                  5  | c      | 18     | 1""", "partial", """
                  id | bucket | status | weight
                 ------------------------------
                  5  | x      | 12     | 1
                  5  | y      | NULL   | 1""", "blanks", """
                  id | col0 | weight
                 --------------------
                  5  | 12   | 1
                  5  | NULL | 2"""));
    }

    @Test
    public void projectionAfterAggregation() {
        var ccs = this.getCCS("""
                CREATE TABLE entries(grp INT, x INT);
                CREATE VIEW counts AS SELECT grp, COUNT(*) AS n, EXPLODE(ARRAY[n, n + 1]) AS value
                    FROM entries WHERE x > 0 GROUP BY grp;
                """);
        ccs.stepWeightOne("INSERT INTO entries VALUES (1, 4), (1, 5), (1, -6);", """
                 grp | n | value
                -----------------
                 1   | 2 | 2
                 1   | 2 | 3""");
    }

    @Test
    public void invalidArguments() {
        this.statementsFailingInCompilation("CREATE VIEW v AS SELECT EXPLODE(42);",
                "EXPLODE requires an ARRAY or MAP");
        this.statementsFailingInCompilation("CREATE VIEW v AS SELECT STACK(0, 1);",
                "STACK row count must be a positive integer literal");
        this.statementsFailingInCompilation("CREATE VIEW v AS SELECT STACK(2, 1, 'a');",
                "STACK column 0 has incompatible types");
    }
}
