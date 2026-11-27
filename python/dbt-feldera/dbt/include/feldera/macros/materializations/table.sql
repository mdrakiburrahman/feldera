{#
    Table materialization for Feldera.

    Creates a CREATE TABLE statement in the pipeline SQL. Tables are input
    sources in Feldera — they define the schema for data ingress (HTTP, Kafka,
    S3, etc.). The model SQL should define the column list, not a SELECT.
#}
{% materialization table, adapter='feldera' %}
    {%- set pipeline_name = config.get('pipeline_name', model.schema) -%}
    {%- set table_name = this.identifier -%}
    {%- set connectors = config.get('connectors', []) -%}
    {%- set stored = config.get('stored', false) -%}
    {%- set table_options = [] -%}
    {%- if stored -%}
        {%- do table_options.append("'materialized' = 'true'") -%}
    {%- endif -%}
    {%- if connectors -%}
        {%- do table_options.append("'connectors' = '" ~ (connectors | tojson) ~ "'") -%}
    {%- endif -%}

    {%- set table_sql -%}
        CREATE TABLE {{ this }} (
            {{ sql }}
        )
        {%- if table_options %}
        WITH ({{ table_options | join(', ') }})
        {%- endif %}
    {%- endset -%}

    {{ adapter.register_table(pipeline_name, table_name, table_sql) }}

    {# dbt requires a 'main' statement to be called during model execution #}
    {% call statement('main') %}
        -- Feldera: table '{{ table_name }}' registered (deployed on-run-end)
    {% endcall %}

    {{ return({'relations': [this]}) }}
{% endmaterialization %}
