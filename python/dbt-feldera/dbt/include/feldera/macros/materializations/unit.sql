{% macro feldera_unit_expected(rows, column_types, column_quoted) %}
    {{ return(get_expected_sql(rows, column_types, column_quoted)) }}
{% endmacro %}

{% materialization unit, adapter='feldera' %}
    {% set result = adapter.run_isolated_unit_test(
        sql, config.get('expected_rows'), config.get('expected_sql'),
        feldera_unit_expected, var('feldera_udf_sql', none),
        var('feldera_udf_rust', none), var('feldera_udf_toml', none)
    ) %}
    {% do store_result('main', response=result[0], agate_table=result[1]) %}
    {{ return({'relations': []}) }}
{% endmaterialization %}
