package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.CompilerOptions;
import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.junit.Test;

public class SparkJsonStringTests extends SqlIoTest {
    @Override
    public CompilerOptions testOptions() {
        CompilerOptions options = super.testOptions();
        options.languageOptions.sparkCompat = true;
        return options;
    }

    @Test
    public void jsonStringAsSparkText() {
        String sql = """
                CREATE TABLE T(j VARCHAR);
                CREATE TYPE inner_t AS (t VARCHAR);
                CREATE TYPE rec_t AS (s VARCHAR, r inner_t, a VARCHAR ARRAY, m MAP<VARCHAR, VARCHAR>);
                CREATE FUNCTION jsonstring_as_rec_t(j VARCHAR) RETURNS rec_t;
                CREATE VIEW V AS SELECT x IS NULL AS absent, x.s, x.r.t, x.a[1] AS a, x.m['k'] AS m
                FROM (SELECT jsonstring_as_rec_t(j) AS x FROM T);""";
        this.getCCS(sql).withStringTrim().stepWeightOne("""
                INSERT INTO T VALUES
                ('{"s": "q\\"\\u0041\\/", "r": {"t": "in"}, "a": ["e"], "m": {"k": "v"}}'),
                ('{"s": 30, "r": {"t": 1.50}, "a": [-0], "m": {"k": 1e7}}'),
                ('{"s": true, "r": {"t": false}, "a": [123456789012345678901234567890], "m": {"k": 0.00012}}'),
                ('{"s": false, "r": {"t": true}, "a": [true], "m": {"k": false}}'),
                ('{"s": true, "r": {"t": false}, "a": [false], "m": {"k": true}}'),
                ('{"s": {"b": 1.50, "a": "\\u0041\\u0001", "a": [1, 2 ]}, "r": {"t": {}}, "a": [{"k": 1}], "m": {"k": {"x": null}}}'),
                ('{"s": [ "s", 1e400 ], "r": {"t": []}, "a": [[true]], "m": {"k": [1e3]}}'),
                ('{"s": null, "r": {"t": null}, "a": [null], "m": {"k": null}}')""", """
                 absent | s | t | a | m
                ------------------------
                 false | q"A/ | in | e | v
                 false | 30 | 1.5 | 0 | 1.0E7
                 false | true | false | 123456789012345678901234567890 | 1.2E-4
                 false | false | true | true | false
                 false | true | false | false | true
                 false | {"b":1.5,"a":"A\\u0001","a":[1,2]} | {} | {"k":1} | {"x":null}
                 false | ["s","Infinity"] | [] | [true] | [1000.0]
                 false |NULL|NULL|NULL|NULL""");
    }
}
