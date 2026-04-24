-- ============================================================================
-- E-COMMERCE REAL-TIME MEDALLION ARCHITECTURE
-- Feldera Incremental SQL Pipeline
-- ============================================================================
-- ============================================================================
-- BRONZE LAYER — Raw Ingestion Tables
-- Append-only, no transformations
-- ============================================================================
CREATE TABLE bronze_clickstream_events (
event_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT,
    session_id VARCHAR,
    event_type VARCHAR,
    page_url VARCHAR,
    product_id BIGINT,
    referrer VARCHAR,
    device_type VARCHAR,
    geo_country VARCHAR,
    geo_region VARCHAR,
    event_timestamp TIMESTAMP NOT NULL,
    ingested_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_clickstream_events",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
CREATE TABLE bronze_orders (
    order_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT,
    order_status VARCHAR,
    order_total DECIMAL(12, 2),
    discount_amount DECIMAL(12, 2),
    shipping_cost DECIMAL(12, 2),
    payment_method VARCHAR,
    coupon_code VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    ingested_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_orders",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
CREATE TABLE bronze_order_items (
order_item_id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    discount_pct DECIMAL(5, 2),
    ingested_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_order_items",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
CREATE TABLE bronze_products (
product_id BIGINT NOT NULL PRIMARY KEY,
    product_name VARCHAR NOT NULL,
    category VARCHAR,
    brand VARCHAR,
    supplier_id BIGINT,
    list_price DECIMAL(10, 2),
    cost_price DECIMAL(10, 2),
    weight_kg DECIMAL(6, 2),
    is_active BOOLEAN,
    updated_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_products",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
CREATE TABLE bronze_inventory_events (
inventory_event_id BIGINT NOT NULL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    event_type VARCHAR NOT NULL,
    quantity_change INT NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    ingested_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_inventory_events",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
CREATE TABLE bronze_customers (
    customer_id BIGINT NOT NULL PRIMARY KEY,
    email VARCHAR,
    signup_date DATE,
    customer_tier VARCHAR,
    geo_country VARCHAR,
    geo_region VARCHAR,
    updated_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_customers",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
CREATE TABLE bronze_suppliers (
    supplier_id BIGINT NOT NULL PRIMARY KEY,
    supplier_name VARCHAR NOT NULL,
    country VARCHAR,
    lead_time_days INT,
    updated_at TIMESTAMP NOT NULL
) WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_input",
            "config": {
                "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_suppliers",
                "mode": "snapshot",
                "azure_skip_signature": "true",
                "transaction_mode": "snapshot"
            }
        }
    }]'
);
-- ============================================================================
-- SILVER LAYER — Cleaned, Validated, Enriched Views
-- All cleaning, validation, deduplication, and joins happen here.
-- ============================================================================
-- ----------------------------------------------------------------------------
-- silver_customers
-- Cleaned customer dimension. All downstream views reference this
-- instead of bronze_customers to ensure gold never touches bronze.
-- ----------------------------------------------------------------------------
CREATE LOCAL VIEW silver_customers AS
SELECT customer_id,
    email,
    signup_date,
    customer_tier,
    geo_country,
    geo_region,
    updated_at
FROM bronze_customers
WHERE customer_id IS NOT NULL
    AND customer_tier IN ('standard', 'silver', 'gold', 'platinum');
-- ----------------------------------------------------------------------------
-- silver_orders_enriched
-- Orders joined with customer profile and aggregated line-item metrics.
-- Validates order_total >= 0 and restricts to known statuses.
-- ----------------------------------------------------------------------------
CREATE LOCAL VIEW silver_orders_enriched AS
SELECT o.order_id,
    o.user_id,
    o.order_status,
    o.order_total,
    o.discount_amount,
    o.shipping_cost,
    o.payment_method,
    o.coupon_code,
    o.created_at,
    o.updated_at,
    c.customer_tier,
    c.geo_country AS customer_country,
    c.geo_region AS customer_region,
    c.signup_date,
    oi.item_count,
    oi.total_quantity,
    oi.gross_item_revenue,
    oi.avg_discount_pct
FROM bronze_orders o
    JOIN silver_customers c ON o.user_id = c.customer_id
    JOIN (
        SELECT order_id,
            COUNT(*) AS item_count,
            SUM(quantity) AS total_quantity,
            SUM(quantity * unit_price) AS gross_item_revenue,
            AVG(discount_pct) AS avg_discount_pct
        FROM bronze_order_items
        WHERE quantity > 0
            AND unit_price >= 0
        GROUP BY order_id
    ) oi ON o.order_id = oi.order_id
WHERE o.order_id IS NOT NULL
    AND o.user_id IS NOT NULL
    AND o.order_total >= 0
    AND o.order_status IN (
        'pending',
        'confirmed',
        'shipped',
        'delivered',
        'cancelled',
        'returned'
    );
-- ----------------------------------------------------------------------------
-- silver_order_items_enriched
-- Line items enriched with product, supplier, order, and customer context.
-- Computes line-level revenue and margin. Filters inactive products.
-- ----------------------------------------------------------------------------
CREATE LOCAL VIEW silver_order_items_enriched AS
SELECT oi.order_item_id,
    oi.order_id,
    oi.product_id,
    oi.quantity,
    oi.unit_price,
    oi.discount_pct,
    oi.quantity * oi.unit_price AS line_gross_revenue,
    oi.quantity * oi.unit_price * (1.0 - COALESCE(oi.discount_pct, 0) / 100.0) AS line_net_revenue,
    oi.quantity * (oi.unit_price - p.cost_price) AS line_gross_margin,
    p.product_name,
    p.category,
    p.brand,
    p.cost_price,
    p.list_price,
    s.supplier_name,
    s.country AS supplier_country,
    s.lead_time_days,
    o.created_at AS order_created_at,
    o.order_status,
    o.user_id,
    c.customer_tier
FROM bronze_order_items oi
    JOIN bronze_products p ON oi.product_id = p.product_id
    JOIN bronze_suppliers s ON p.supplier_id = s.supplier_id
    JOIN bronze_orders o ON oi.order_id = o.order_id
    JOIN silver_customers c ON o.user_id = c.customer_id
WHERE oi.quantity > 0
    AND oi.unit_price >= 0
    AND p.is_active = TRUE
    AND o.order_status IN (
        'pending',
        'confirmed',
        'shipped',
        'delivered',
        'cancelled',
        'returned'
    );
-- ----------------------------------------------------------------------------
-- silver_confirmed_order_items
-- Subset of order items excluding cancelled and returned orders.
-- Business rule filtering lives here so gold only aggregates.
-- ----------------------------------------------------------------------------
CREATE LOCAL VIEW silver_confirmed_order_items AS
SELECT *
FROM silver_order_items_enriched
WHERE order_status NOT IN ('cancelled', 'returned');
-- ----------------------------------------------------------------------------
-- silver_inventory_current
-- Running inventory position per product per warehouse.
-- Computed from cumulative sum of inventory events.
-- ----------------------------------------------------------------------------
CREATE LOCAL VIEW silver_inventory_current AS
SELECT ie.product_id,
    ie.warehouse_id,
    p.product_name,
    p.category,
    p.brand,
    s.supplier_name,
    s.lead_time_days,
    SUM(ie.quantity_change) AS current_stock,
    SUM(
        CASE
            WHEN ie.event_type = 'restock' THEN ie.quantity_change
            ELSE 0
        END
    ) AS total_restocked,
    SUM(
        CASE
            WHEN ie.event_type = 'sale_reserve' THEN ABS(ie.quantity_change)
            ELSE 0
        END
    ) - SUM(
        CASE
            WHEN ie.event_type = 'cancellation_restock' THEN ie.quantity_change
            ELSE 0
        END
    ) AS total_sold,
    SUM(
        CASE
            WHEN ie.event_type = 'return_restock' THEN ie.quantity_change
            ELSE 0
        END
    ) AS total_returned
FROM bronze_inventory_events ie
    JOIN bronze_products p ON ie.product_id = p.product_id
    JOIN bronze_suppliers s ON p.supplier_id = s.supplier_id
WHERE ie.quantity_change IS NOT NULL
    AND ie.product_id IS NOT NULL
GROUP BY ie.product_id,
    ie.warehouse_id,
    p.product_name,
    p.category,
    p.brand,
    s.supplier_name,
    s.lead_time_days;
-- ----------------------------------------------------------------------------
-- silver_inventory_by_supplier
-- Supplier-level inventory rollup. Keeps gold views free of inline subqueries.
-- ----------------------------------------------------------------------------
CREATE LOCAL VIEW silver_inventory_by_supplier AS
SELECT supplier_name,
    SUM(current_stock) AS total_current_stock,
    SUM(total_sold) AS total_sold,
    SUM(total_restocked) AS total_restocked,
    SUM(total_returned) AS total_returned
FROM silver_inventory_current
GROUP BY supplier_name;
-- ============================================================================
-- GOLD LAYER — Business Metrics & Analytics
-- Pure aggregation over silver views. No filtering, no bronze references.
-- ============================================================================
-- ----------------------------------------------------------------------------
-- gold_supplier_performance
-- Supplier-level metrics combining order revenue/margin with inventory.
-- ----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW gold_supplier_performance
WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_output",
            "config": {
                "uri": "file:///var/feldera/delta/gold_supplier_performance",
                "mode": "truncate"
            }
        },
        "enable_output_buffer": true,
        "max_output_buffer_time_millis": 10000
    }]'
)
AS with orders_by_supplier as (
    select oi.supplier_name,
        oi.supplier_country,
        oi.lead_time_days,
        COUNT(DISTINCT oi.product_id) AS products_sold,
        COUNT(DISTINCT oi.order_id) AS orders_fulfilled,
        SUM(oi.quantity) AS total_units_sold,
        SUM(oi.line_net_revenue) AS total_net_revenue,
        SUM(oi.line_gross_margin) AS total_gross_margin,
        SUM(oi.line_gross_margin) / NULLIF(SUM(oi.line_net_revenue), 0) AS avg_margin_pct,
        AVG(oi.discount_pct) AS avg_discount_applied,
        CAST(
            SUM(
                CASE
                    WHEN oi.order_status = 'delivered' THEN 1
                    ELSE 0
                END
            ) AS DOUBLE
        ) / NULLIF(CAST(COUNT(*) AS DOUBLE), 0) AS reliability_score
    from silver_confirmed_order_items oi
    group by oi.supplier_name,
oi.supplier_country,
oi.lead_time_days
)
SELECT oi.supplier_name,
    oi.supplier_country,
    oi.lead_time_days,
    products_sold,
    orders_fulfilled,
    total_units_sold,
    total_net_revenue,
    total_gross_margin,
    avg_margin_pct,
    avg_discount_applied,
    reliability_score,
    inv.total_current_stock,
    inv.total_sold AS inventory_units_sold,
    inv.total_restocked AS inventory_units_restocked,
    inv.total_returned AS inventory_units_returned
FROM orders_by_supplier oi
    JOIN silver_inventory_by_supplier inv ON oi.supplier_name = inv.supplier_name;
-- ----------------------------------------------------------------------------
-- gold_inventory_risk
-- Stock risk scoring: compares days of stock remaining against supplier
-- lead time to flag CRITICAL / WARNING / OK products.
-- ----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW gold_inventory_risk
WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_output",
            "config": {
                "uri": "file:///var/feldera/delta/gold_inventory_risk",
                "mode": "truncate"
            }
        },
        "enable_output_buffer": true,
        "max_output_buffer_time_millis": 10000
    }]'
)
AS
SELECT inv.product_id,
    inv.product_name,
    inv.category,
    inv.brand,
    inv.supplier_name,
    inv.lead_time_days,
    inv.total_stock_all_warehouses,
    sales.units_sold_recent,
    sales.avg_daily_units,
    CASE
        WHEN sales.avg_daily_units > 0 THEN inv.total_stock_all_warehouses / sales.avg_daily_units
        ELSE NULL
    END AS days_of_stock_remaining,
    CASE
        WHEN sales.avg_daily_units > 0
        AND (
            inv.total_stock_all_warehouses / sales.avg_daily_units
        ) < inv.lead_time_days * 1.5 THEN 'CRITICAL'
        WHEN sales.avg_daily_units > 0
        AND (
            inv.total_stock_all_warehouses / sales.avg_daily_units
        ) < inv.lead_time_days * 3.0 THEN 'WARNING'
        ELSE 'OK'
    END AS stock_risk_level,
    sales.net_revenue_recent,
    sales.gross_margin_recent
FROM (
        SELECT product_id,
            product_name,
            category,
            brand,
            supplier_name,
            lead_time_days,
            SUM(current_stock) AS total_stock_all_warehouses
        FROM silver_inventory_current
        GROUP BY product_id,
            product_name,
            category,
            brand,
            supplier_name,
            lead_time_days
    ) inv
    JOIN (
        SELECT product_id,
            SUM(quantity) AS units_sold_recent,
            SUM(quantity) / 30.0 AS avg_daily_units,
            SUM(line_net_revenue) AS net_revenue_recent,
            SUM(line_gross_margin) AS gross_margin_recent
        FROM silver_confirmed_order_items
        GROUP BY product_id
    ) sales ON inv.product_id = sales.product_id;
-- ----------------------------------------------------------------------------
-- gold_order_status_summary
-- Status distribution across all orders. Changes visibly with every MERGE
-- commit that transitions order statuses (the core incremental demo).
-- ----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW gold_order_status_summary
WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_output",
            "config": {
                "uri": "file:///var/feldera/delta/gold_order_status_summary",
                "mode": "truncate"
            }
        },
        "enable_output_buffer": true,
        "max_output_buffer_time_millis": 10000
    }]'
)
AS
SELECT o.order_status,
    COUNT(DISTINCT o.order_id) AS order_count,
    COALESCE(SUM(o.order_total), 0) AS total_revenue,
    AVG(o.order_total) AS avg_order_value,
    COUNT(DISTINCT o.user_id) AS unique_customers
FROM silver_orders_enriched o
GROUP BY o.order_status;
-- ----------------------------------------------------------------------------
-- gold_weekly_revenue_trend
-- Revenue time-series with window functions: week-over-week change, 4-week
-- moving average, and cumulative YTD. When orders are cancelled via MERGE,
-- historical weeks' revenue decreases and all window computations cascade.
-- ----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW gold_weekly_revenue_trend
WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_output",
            "config": {
                "uri": "file:///var/feldera/delta/gold_weekly_revenue_trend",
                "mode": "truncate"
            }
        },
        "enable_output_buffer": true,
        "max_output_buffer_time_millis": 10000
    }]'
)
AS
SELECT week_start,
    category,
    weekly_net_revenue,
    weekly_gross_margin,
    order_count,
    units_sold,
    weekly_net_revenue - LAG(weekly_net_revenue, 1) OVER (
        PARTITION BY category
        ORDER BY week_start
    ) AS revenue_wow_change,
    (
        weekly_net_revenue - LAG(weekly_net_revenue, 1) OVER (
            PARTITION BY category
            ORDER BY week_start
        )
    ) / NULLIF(
        LAG(weekly_net_revenue, 1) OVER (
            PARTITION BY category
            ORDER BY week_start
        ),
        0
    ) AS revenue_wow_pct_change,
    AVG(weekly_net_revenue) OVER (
        PARTITION BY category
ORDER BY week_start RANGE BETWEEN INTERVAL 3 WEEKS PRECEDING
    AND CURRENT ROW
    ) AS revenue_4wk_moving_avg,
    AVG(weekly_gross_margin) OVER (
        PARTITION BY category
ORDER BY week_start RANGE BETWEEN INTERVAL 3 WEEKS PRECEDING
    AND CURRENT ROW
    ) AS margin_4wk_moving_avg,
    SUM(weekly_net_revenue) OVER (
        PARTITION BY category,
        EXTRACT(
            YEAR
            FROM week_start
        )
        ORDER BY week_start RANGE UNBOUNDED PRECEDING
    ) AS cumulative_ytd_revenue
FROM (
        SELECT DATE_TRUNC(oi.order_created_at, WEEK) AS week_start,
            oi.category,
            SUM(oi.line_net_revenue) AS weekly_net_revenue,
            SUM(oi.line_gross_margin) AS weekly_gross_margin,
            COUNT(DISTINCT oi.order_id) AS order_count,
            SUM(oi.quantity) AS units_sold
        FROM silver_confirmed_order_items oi
        GROUP BY DATE_TRUNC(oi.order_created_at, WEEK),
            oi.category
    );
-- ----------------------------------------------------------------------------
-- gold_cancellation_impact
-- Cancellation rates with cumulative and 4-week moving windows. Each MERGE
-- that moves orders to cancelled shifts the running totals incrementally.
-- ----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW gold_cancellation_impact
WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_output",
            "config": {
                "uri": "file:///var/feldera/delta/gold_cancellation_impact",
                "mode": "truncate"
            }
        },
        "enable_output_buffer": true,
        "max_output_buffer_time_millis": 10000
    }]'
)
AS
SELECT category,
    week_start,
    weekly_cancelled_orders,
    weekly_total_orders,
    weekly_cancelled_revenue,
    weekly_total_revenue,
    CAST(
        SUM(weekly_cancelled_orders) OVER (
            PARTITION BY category
            ORDER BY week_start RANGE UNBOUNDED PRECEDING
        ) AS DOUBLE
    ) / NULLIF(
        CAST(
            SUM(weekly_total_orders) OVER (
                PARTITION BY category
                ORDER BY week_start RANGE UNBOUNDED PRECEDING
            ) AS DOUBLE
        ),
        0
    ) AS cumulative_cancellation_rate,
    SUM(weekly_cancelled_revenue) OVER (
        PARTITION BY category
        ORDER BY week_start RANGE UNBOUNDED PRECEDING
    ) AS cumulative_cancelled_revenue,
    CAST(
        SUM(weekly_cancelled_orders) OVER (
            PARTITION BY category
ORDER BY week_start RANGE BETWEEN INTERVAL 3 WEEKS PRECEDING
    AND CURRENT ROW
        ) AS DOUBLE
    ) / NULLIF(
        CAST(
            SUM(weekly_total_orders) OVER (
                PARTITION BY category
ORDER BY week_start RANGE BETWEEN INTERVAL 3 WEEKS PRECEDING
    AND CURRENT ROW
            ) AS DOUBLE
        ),
        0
    ) AS cancellation_rate_4wk
FROM (
        SELECT oi.category,
            DATE_TRUNC(oi.order_created_at, WEEK) AS week_start,
            COUNT(
                DISTINCT CASE
                    WHEN oi.order_status = 'cancelled' THEN oi.order_id
                END
            ) AS weekly_cancelled_orders,
            COUNT(DISTINCT oi.order_id) AS weekly_total_orders,
            SUM(
                CASE
                    WHEN oi.order_status = 'cancelled' THEN oi.line_net_revenue
                    ELSE 0
                END
            ) AS weekly_cancelled_revenue,
            SUM(oi.line_net_revenue) AS weekly_total_revenue
        FROM silver_order_items_enriched oi
        GROUP BY oi.category,
            DATE_TRUNC(oi.order_created_at, WEEK)
    );

-- ----------------------------------------------------------------------------
-- gold_realtime_inventory_alerts
-- Filtered view of CRITICAL inventory items. Products enter and leave this
-- view as stock is reserved (sale_reserve) or restored (cancellation_restock).
-- ----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW gold_realtime_inventory_alerts
WITH (
    'connectors' = '[{
        "transport": {
            "name": "delta_table_output",
            "config": {
                "uri": "file:///var/feldera/delta/gold_realtime_inventory_alerts",
                "mode": "truncate"
            }
        },
        "enable_output_buffer": true,
        "max_output_buffer_time_millis": 10000
    }]'
)
AS
SELECT *
FROM gold_inventory_risk
WHERE stock_risk_level = 'CRITICAL';