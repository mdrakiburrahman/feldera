{#
    Feldera seed macro overrides.

    Feldera seeds work differently from traditional databases:
    - A CREATE TABLE DDL is sent to the pipeline state manager
    - Deployment (compilation + start) is deferred until the on-run-end hook
    - After the pipeline is running, seed data is pushed via HTTP ingress

    These macros override dbt's default seed helpers via adapter dispatch.
#}

{% macro feldera__create_csv_table(model, agate_table) %}
    {%- set pipeline_name = model.schema -%}
    {%- set table_name = model.name -%}
    {%- set column_override = model['config'].get('column_types', {}) -%}
    {%- set quote_seed_column = model['config'].get('quote_columns', none) -%}

    {# Build column definitions from agate table schema #}
    {%- set column_defs = [] -%}
    {%- for col_name in agate_table.column_names -%}
        {%- set inferred_type = adapter.convert_type(agate_table, loop.index0) -%}
        {%- set type = column_override.get(col_name, inferred_type) -%}
        {%- set column_name = (col_name | string) -%}
        {%- set col_def = adapter.quote_seed_column(column_name, quote_seed_column) ~ ' ' ~ type -%}
        {%- do column_defs.append(col_def) -%}
    {%- endfor -%}

    {# Quote the table name to handle reserved words like 'date' #}
    {%- set table_sql -%}
        CREATE TABLE "{{ table_name }}" (
            {{ column_defs | join(', ') }}
        )
        {% if var('feldera_safe_seed_reuse', false) %}
        WITH ('materialized' = 'true')
        {% endif %}
    {%- endset -%}

    {# Send the table DDL to the pipeline state manager — deployment is deferred to on-run-end #}
    {{ adapter.register_table(pipeline_name, table_name, table_sql) }}
    {% if var('feldera_safe_seed_reuse', false) %}
        {{ adapter.stash_seed(pipeline_name, table_name, agate_table, column_override) }}
    {% endif %}

    {{ return(table_sql) }}
{% endmacro %}


{% macro feldera__reset_csv_table(model, full_refresh, old_relation, agate_table) %}
    {%- set pipeline_name = model.schema -%}

    {% if full_refresh and var('feldera_safe_seed_reuse', false) %}
        {{ exceptions.raise_compiler_error("Safe seed reuse does not permit --full-refresh; use a cold rebuild") }}
    {% elif full_refresh %}
        {{ adapter.stop_pipeline(pipeline_name) }}
    {% endif %}

    {% set sql = feldera__create_csv_table(model, agate_table) %}
    {{ return(sql) }}
{% endmacro %}


{% macro feldera__load_csv_rows(model, agate_table) %}
    {%- set pipeline_name = model.schema -%}
    {%- set table_name = model.name -%}
    {%- set column_types = model['config'].get('column_types', {}) -%}

    {# Stash data for deferred push after pipeline deployment #}
    {% if not var('feldera_safe_seed_reuse', false) %}
        {{ adapter.stash_seed(pipeline_name, table_name, agate_table, column_types) }}
    {% endif %}

    {{ return("-- seed data stashed for deferred push to " ~ pipeline_name ~ "." ~ table_name) }}
{% endmacro %}


{% macro feldera__get_csv_sql(create_or_truncate_sql, insert_sql) %}
    {{ create_or_truncate_sql }}
{% endmacro %}

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
    {% if var('feldera_safe_seed_reuse', false) and state.failed %}
        {{ exceptions.raise_compiler_error("Safe seed reuse refuses to deploy after a failed or skipped node") }}
    {% endif %}
    {% if state.deploy %}
        {{ adapter.finalize_seeds(flags.FULL_REFRESH, var('feldera_udf_sql', none), var('feldera_udf_rust', none), var('feldera_udf_toml', none), var('spark_compat', none), var('feldera_safe_seed_reuse', false)) }}
    {% endif %}
{% endmacro %}
