INSTALL delta;
LOAD delta;

CREATE OR REPLACE VIEW gold_supplier_performance AS SELECT * FROM delta_scan('/delta/gold_supplier_performance');
CREATE OR REPLACE VIEW gold_inventory_risk AS SELECT * FROM delta_scan('/delta/gold_inventory_risk');
CREATE OR REPLACE VIEW gold_order_status_summary AS SELECT * FROM delta_scan('/delta/gold_order_status_summary');
CREATE OR REPLACE VIEW gold_weekly_revenue_trend AS SELECT * FROM delta_scan('/delta/gold_weekly_revenue_trend');
CREATE OR REPLACE VIEW gold_cancellation_impact AS SELECT * FROM delta_scan('/delta/gold_cancellation_impact');
CREATE OR REPLACE VIEW gold_realtime_inventory_alerts AS SELECT * FROM delta_scan('/delta/gold_realtime_inventory_alerts');