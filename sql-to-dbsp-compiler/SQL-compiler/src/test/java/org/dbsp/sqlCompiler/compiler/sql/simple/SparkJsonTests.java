package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.junit.Test;

public class SparkJsonTests extends SqlIoTest {
    @Test
    public void fromJson() {
        this.qst("""
                SELECT FROM_JSON('{''eventType'':''open'',''eventtype'':''wrong''} {}', 'eventType STRING').eventType AS v;
                 v
                ---
                 open
                (1 row)

                SELECT TO_JSON(FROM_JSON('[{"URL":"a","ID":"b"},{"ID":"c","URL":null},null]',
                                         'array<struct<ID:string,URL:string>>')) AS v;
                 v
                ---
                 [{"ID":"b","URL":"a"},{"ID":"c"},null]
                (1 row)

                SELECT FROM_JSON('{"x":12,"y":null}', 'MAP<STRING,STRING>')['x'] AS v;
                 v
                ---
                 12
                (1 row)

                SELECT FROM_JSON('{"s":1.50}', 's STRING').s AS v,
                       FROM_JSON('{"s": { "x":1e7 }}', 's STRING').s AS obj;
                 v | obj
                ---------
                 1.50 | {"x":1e7}
                (1 row)

                SELECT FROM_JSON('not json', 'ARRAY<INT>') IS NULL AS bad,
                       FROM_JSON('{}', 'missing STRING').missing IS NULL AS absent;
                 bad | absent
                --------------
                 true | true
                (1 row)""");
    }

    @Test
    public void quotedSchemaFieldNames() {
        this.qst("""
                SELECT TO_JSON(FROM_JSON('{"tags":{"a":"b"},"items":[{"is`ready":true}],"display name":"A"}',
                       'STRUCT<`display name`: STRING, `items`: ARRAY<STRUCT<`is``ready`: BOOLEAN>>, `tags`: MAP<STRING,STRING>>')) AS v;
                 v
                ---
                 {"display name":"A","items":[{"is`ready":true}],"tags":{"a":"b"}}
                (1 row)

                SELECT TO_JSON(FROM_JSON('{"a,b":7}', '`a,b` INT')) AS v;
                 v
                ---
                 {"a,b":7}
                (1 row)""");
        this.queryFailingInCompilation("SELECT FROM_JSON('{}', 'STRUCT<`unfinished: INT>')", "Unterminated quoted field name");
    }

    @Test
    public void toJson() {
        this.qst("""
                SELECT TO_JSON(NAMED_STRUCT('z', price, 'unused', CAST(NULL AS INT), 'a', qty)) AS v
                FROM (VALUES (7, 2)) t(price, qty);
                 v
                ---
                 {"z":7,"a":2}
                (1 row)

                SELECT TO_JSON(ARRAY[3, CAST(NULL AS INT), 1]) AS items,
                       TO_JSON(CAST(NULL AS INT ARRAY)) IS NULL AS absent;
                 items | absent
                ----------------
                 [3,null,1] | true
                (1 row)

                SELECT TO_JSON(NAMED_STRUCT('ts', TIMESTAMP '2024-02-29 12:34:56.123456', 'price', CAST(7.5 AS DECIMAL(8,2)),
                                            'day', DATE '2024-02-29', 'weight', CAST(2 AS DOUBLE))) AS v;
                 v
                ---
                 {"ts":"2024-02-29T12:34:56.123Z","price":7.50,"day":"2024-02-29","weight":2.0}
                (1 row)

                SELECT NAMED_STRUCT('qty', 11, 'price', 5).qty AS v;
                 v
                ---
                 11
                (1 row)""");
    }

    @Test
    public void getJsonObject() {
        this.qst("""
                SELECT GET_JSON_OBJECT('{"field":"not quoted"}', '$.field') AS s,
                       GET_JSON_OBJECT('{"rows":[{"z": 1, "a":2}]}', '$.rows[0]') AS obj;
                 s | obj
                ---------
                 not quoted | {"z":1,"a":2}
                (1 row)

                SELECT GET_JSON_OBJECT('broken', '$.a') IS NULL AS bad,
                       GET_JSON_OBJECT('{"a":null}', '$.a') IS NULL AS n,
                       GET_JSON_OBJECT('{}', '$.a') IS NULL AS missing;
                 bad | n | missing
                -------------------
                 true | true | true
                (1 row)

                SELECT COALESCE('v:' || GET_JSON_OBJECT(j, p), 'missing') FROM (VALUES
                   ('{"a.b":[["first"],["next"]]}', '$[''a.b''][*][*]'),
                   ('{"a":null,"a":"first","a":"last"}', '$.a'),
                   ('[{"a":[1]},{"a":[]},{"a":[2]}]', '$[*].a[*]'),
                   ('["single"]', '$[*]'), ('[null]', '$[0]'),
                   ('{"a":1}', '$[')) t(j,p);
                 selected
                ---------
                 v:["first","next"]
                 v:first
                 v:[[1],[],[2]]
                 v:"single"
                 v:null
                 missing
                (6 rows)""");
    }

    @Test
    public void unsupportedForms() {
        this.queryFailingInCompilation("SELECT FROM_JSON('{}', 'MAP<INT,STRING>')", "Only STRING keys");
        this.queryFailingInCompilation("SELECT FROM_JSON('malformed', '_corrupt_record STRING')", "Corrupt-record capture");
        this.queryFailingInCompilation("SELECT NAMED_STRUCT('a', 1, 'A', 2)", "ambiguous field name");
    }
}
