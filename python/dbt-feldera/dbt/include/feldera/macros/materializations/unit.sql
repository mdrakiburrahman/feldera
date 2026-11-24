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

{% macro feldera_finalize_run() %}
    {% set state = namespace(deploy=false, failed=false) %}
    {% for result in results %}
        {% if result.node.resource_type in ['model', 'seed'] and result.status == 'success' %}
            {% set state.deploy = true %}
        {% endif %}
        {% if result.status in ['error', 'fail', 'skipped', 'runtime error'] %}
            {% set state.failed = true %}
        {% endif %}
    {% endfor %}
    {% if var('feldera_safe_seed_reuse', false) and state.deploy and state.failed %}
        {{ exceptions.raise_compiler_error("Safe seed reuse refuses to deploy after a failed or skipped node") }}
    {% endif %}
    {% if state.deploy %}
        {{ adapter.finalize_seeds(flags.FULL_REFRESH, var('feldera_udf_sql', none), var('feldera_udf_rust', none), var('feldera_udf_toml', none), var('feldera_safe_seed_reuse', false)) }}
    {% endif %}
{% endmacro %}
