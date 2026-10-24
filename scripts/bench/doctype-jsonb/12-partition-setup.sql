-- DocType JSONB benchmark: month partitioning as the kernel builds it (V7__month_partitions_and_materialised.sql,
-- partition: month) and the materialised Currency column (projected: materialised), for the "partition" limits
-- mode. The hanoi orders and waybills of astra_doc are copied into astra_doc_txn with created_at spread over 18
-- months (2025-05 .. 2026-10, one partition each), the shape of a table after a year and a half in production.
-- Run as the bootstrap superuser after 10-limits-setup.sql and 11-partial-projection-indexes.sql.
\set ON_ERROR_STOP on
\timing on

SET ROLE astra_app;
DROP TABLE IF EXISTS astra_doc_txn CASCADE;
CREATE TABLE astra_doc_txn (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    text        NOT NULL,
    doctype      text        NOT NULL,
    name         text        NOT NULL,
    docstatus    smallint    NOT NULL DEFAULT 0,
    owner        text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    modified_at  timestamptz NOT NULL DEFAULT now(),
    version      integer     NOT NULL DEFAULT 1,
    data         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    amended_from text,
    m_cod_amount bigint,
    PRIMARY KEY (id, created_at),
    CONSTRAINT astra_doc_txn_tenant_doctype_name_key UNIQUE (tenant_id, doctype, name, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE astra_doc_txn_default PARTITION OF astra_doc_txn DEFAULT;
DO $$
DECLARE m date;
BEGIN
  FOR m IN SELECT generate_series(date '2025-05-01', date '2026-12-01', interval '1 month')::date LOOP
    EXECUTE format('CREATE TABLE %I PARTITION OF astra_doc_txn FOR VALUES FROM (%L) TO (%L)', 'astra_doc_txn_' || to_char(m, 'YYYY_MM'),
                   (m::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh'), ((m + interval '1 month')::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh'));
  END LOOP;
END $$;
ALTER TABLE astra_doc_txn ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_doc_txn FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_doc_txn_tenant_isolation ON astra_doc_txn
    USING (tenant_id = current_setting('astra.tenant', true)) WITH CHECK (tenant_id = current_setting('astra.tenant', true));

-- Materialised COD exactly as the kernel's trigger computes it (hundredths).
CREATE FUNCTION astra_doc_txn_materialise() RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    NEW.m_cod_amount := CASE WHEN pg_input_is_valid(astra_field(NEW.data, 'cod_amount'), 'numeric') THEN
        CASE WHEN abs((astra_field(NEW.data, 'cod_amount'))::numeric) < 90000000000000000
             THEN round((astra_field(NEW.data, 'cod_amount'))::numeric * 100)::bigint END END;
    RETURN NEW;
END $fn$;
CREATE TRIGGER astra_doc_txn_materialise BEFORE INSERT OR UPDATE OF data ON astra_doc_txn FOR EACH ROW EXECUTE FUNCTION astra_doc_txn_materialise();

SELECT set_config('astra.tenant', 'hanoi-express', false);
-- Day k of 540 for the k-th order by name: the 18 months fill evenly; the time of day and the modified lag are kept.
INSERT INTO astra_doc_txn (id, tenant_id, doctype, name, docstatus, owner, created_at, modified_at, version, data, amended_from)
SELECT d.id, d.tenant_id, d.doctype, d.name, d.docstatus, d.owner,
       timestamptz '2025-05-01 00:00+07' + (o.i % 540) * interval '1 day' + (d.created_at - date_trunc('day', d.created_at)),
       timestamptz '2025-05-01 00:00+07' + (o.i % 540) * interval '1 day' + (d.created_at - date_trunc('day', d.created_at)) + (d.modified_at - d.created_at),
       d.version, d.data, d.amended_from
FROM bench_orders o JOIN astra_doc d ON d.doctype = 'CustomerOrder' AND d.name = o.name;
INSERT INTO astra_doc_txn (id, tenant_id, doctype, name, docstatus, owner, created_at, modified_at, version, data, amended_from)
SELECT d.id, d.tenant_id, d.doctype, d.name, d.docstatus, d.owner,
       timestamptz '2025-05-01 00:00+07' + (o.i % 540) * interval '1 day' + (d.created_at - date_trunc('day', d.created_at)),
       timestamptz '2025-05-01 00:00+07' + (o.i % 540) * interval '1 day' + (d.created_at - date_trunc('day', d.created_at)) + (d.modified_at - d.created_at),
       d.version, d.data, d.amended_from
FROM bench_orders o JOIN astra_doc d ON d.doctype = 'Waybill' AND d.name = o.waybill;

-- The kernel's index set on the partitioned table (built here directly; the kernel builds them per partition, online).
CREATE INDEX astra_doc_txn_list_idx       ON astra_doc_txn (tenant_id, doctype, modified_at DESC);
CREATE INDEX astra_doc_txn_created_idx    ON astra_doc_txn (tenant_id, doctype, created_at DESC);
CREATE INDEX astra_doc_txn_pf_customer    ON astra_doc_txn (tenant_id, doctype, astra_field(data, 'customer'), modified_at DESC)   WHERE astra_field(data, 'customer') IS NOT NULL;
CREATE INDEX astra_doc_txn_pf_zone        ON astra_doc_txn (tenant_id, doctype, astra_field(data, 'zone'), modified_at DESC)       WHERE astra_field(data, 'zone') IS NOT NULL;
CREATE INDEX astra_doc_txn_pf_ops_status  ON astra_doc_txn (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC) WHERE astra_field(data, 'ops_status') IS NOT NULL;
CREATE INDEX astra_doc_txn_pm_cod_amount  ON astra_doc_txn (tenant_id, doctype, m_cod_amount) WHERE m_cod_amount IS NOT NULL;
-- The same materialised column and index on the unpartitioned table, for the like-for-like range comparison.
UPDATE astra_doc SET m_cod_amount = round((data->>'cod_amount')::numeric * 100)::bigint WHERE doctype = 'CustomerOrder';
CREATE INDEX astra_doc_pm_cod_amount ON astra_doc (tenant_id, doctype, m_cod_amount) WHERE m_cod_amount IS NOT NULL;
RESET ROLE;

VACUUM ANALYZE astra_doc_txn;
VACUUM ANALYZE astra_doc;
SELECT tableoid::regclass AS partition, count(*) FROM astra_doc_txn GROUP BY 1 ORDER BY 1;
