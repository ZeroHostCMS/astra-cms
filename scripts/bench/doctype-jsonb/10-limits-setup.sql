-- DocType JSONB benchmark, limits phase: bring the database to the index set the optimised kernel creates,
-- add the Waybill documents that carry the frequently changing status, and the helper tables the pgbench
-- workloads draw keys from. Run as the bootstrap superuser after the original chain (01..05).
--
-- Optimised kernel index set on astra_doc (what ProjectionIndexer + V6 produce after this change):
--   pkey, UNIQUE (tenant_id, doctype, name), list (tenant_id, doctype, modified_at DESC),
--   created (tenant_id, doctype, created_at DESC), and per projected field
--   (tenant_id, doctype, astra_field(data, f), modified_at DESC). No GIN, no ->> indexes.
-- orders_rel gets the equivalent B-trees so the comparison stays like for like.
\set ON_ERROR_STOP on
\timing on

DROP TABLE IF EXISTS astra_doc_part CASCADE;
DROP TABLE IF EXISTS gen CASCADE;

SET ROLE astra_app;
DROP INDEX IF EXISTS astra_doc_data_gin;
DROP INDEX IF EXISTS astra_doc_p_customer;
DROP INDEX IF EXISTS astra_doc_p_zone;
DROP INDEX IF EXISTS astra_doc_p_ops_status;
DROP INDEX IF EXISTS astra_doc_lp_customer;
DROP INDEX IF EXISTS astra_doc_lp_zone;              -- left by 06-lean-reload.sql
ALTER INDEX IF EXISTS astra_doc_lp_ops_status_mod RENAME TO astra_doc_pf_ops_status;
CREATE INDEX IF NOT EXISTS astra_doc_pf_customer ON astra_doc (tenant_id, doctype, astra_field(data, 'customer'), modified_at DESC);
CREATE INDEX IF NOT EXISTS astra_doc_pf_zone     ON astra_doc (tenant_id, doctype, astra_field(data, 'zone'), modified_at DESC);
CREATE INDEX IF NOT EXISTS astra_doc_pf_ops_status ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC);
CREATE INDEX IF NOT EXISTS astra_doc_created_idx ON astra_doc (tenant_id, doctype, created_at DESC);

DROP INDEX IF EXISTS orders_rel_customer;
DROP INDEX IF EXISTS orders_rel_zone;
DROP INDEX IF EXISTS orders_rel_ops_status;
CREATE INDEX orders_rel_customer   ON orders_rel (tenant_id, customer, modified_at DESC);
CREATE INDEX orders_rel_zone       ON orders_rel (tenant_id, zone, modified_at DESC);
CREATE INDEX orders_rel_ops_status ON orders_rel (tenant_id, ops_status, modified_at DESC);
CREATE INDEX IF NOT EXISTS orders_rel_created_idx ON orders_rel (tenant_id, created_at DESC);

-- Waybill: one narrow document per hanoi order, the document a scan event changes (waybill.doctype.yaml).
-- scan_log holds 3 entries on average, what an order in flight carries.
SELECT set_config('astra.tenant', 'hanoi-express', false);
INSERT INTO astra_doc (tenant_id, doctype, name, docstatus, owner, created_at, modified_at, data)
SELECT 'hanoi-express', 'Waybill', data->>'waybill', 0, owner, created_at, modified_at,
       jsonb_build_object('order', name, 'receiver_name', data->>'receiver_name', 'ops_status', data->>'ops_status',
                          'current_hub', 'HN-' || lpad((abs(hashtext(name)) % 40)::text, 2, '0'),
                          'scan_log', jsonb_build_array(
                              jsonb_build_object('at', created_at, 'status', 'Created', 'hub', 'HN-01', 'by', owner),
                              jsonb_build_object('at', created_at + interval '2 hours', 'status', 'Picked Up', 'hub', 'HN-01', 'by', owner),
                              jsonb_build_object('at', created_at + interval '5 hours', 'status', 'At Hub', 'hub', 'HN-07', 'by', owner)))
FROM astra_doc
WHERE doctype = 'CustomerOrder' AND tenant_id = 'hanoi-express' AND data ? 'waybill' AND name LIKE 'ORD-%';
RESET ROLE;

-- Key tables the workloads pick random rows from (no RLS: they hold names only). i is dense 1..N.
DROP TABLE IF EXISTS bench_orders;
CREATE TABLE bench_orders AS
SELECT row_number() OVER (ORDER BY name)::int AS i, name, data->>'waybill' AS waybill, data->>'customer' AS customer
FROM astra_doc WHERE doctype = 'CustomerOrder' AND tenant_id = 'hanoi-express' AND data ? 'waybill' AND name LIKE 'ORD-%';
ALTER TABLE bench_orders ADD PRIMARY KEY (i);
GRANT SELECT ON bench_orders TO astra_app;
SELECT count(*) AS hanoi_orders_with_waybill FROM bench_orders;

-- Naming series counter, exactly as V1__astra_doc.sql: one row per (tenant, series key), locked until commit.
SET ROLE astra_app;
CREATE TABLE IF NOT EXISTS astra_series (
    tenant_id  text   NOT NULL,
    series_key text   NOT NULL,
    current    bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, series_key)
);
ALTER TABLE astra_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_series FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS astra_series_tenant_isolation ON astra_series;
CREATE POLICY astra_series_tenant_isolation ON astra_series
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));
RESET ROLE;

-- Sequence-named insert variant: no hot counter row.
DROP SEQUENCE IF EXISTS bench_order_seq;
CREATE SEQUENCE bench_order_seq CACHE 100;
ALTER SEQUENCE bench_order_seq OWNER TO astra_app;

VACUUM ANALYZE astra_doc;
VACUUM ANALYZE orders_rel;
VACUUM ANALYZE bench_orders;
