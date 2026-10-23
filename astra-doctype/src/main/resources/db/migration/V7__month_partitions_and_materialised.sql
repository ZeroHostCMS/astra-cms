-- 1. astra_doc_txn: the document table for DocTypes declared with "partition: month" (high-volume transaction
--    DocTypes such as orders and waybills). Same columns as astra_doc, partitioned by the month of created_at, so
--    a month can be vacuumed, detached and archived on its own and the indexes of recent months stay small.
--    Master data stays in astra_doc, where a lookup by name reads a single index.
--
--    A unique constraint on a partitioned table must contain the partition key: the name is unique per
--    (tenant, doctype, created_at). Names from naming series and random names are unique by construction; for
--    names given explicitly (field naming, amendments) the kernel checks across partitions under an advisory lock.

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
    PRIMARY KEY (id, created_at),
    CONSTRAINT astra_doc_txn_tenant_doctype_name_key UNIQUE (tenant_id, doctype, name, created_at)
) PARTITION BY RANGE (created_at);

-- Rows outside the monthly partitions that the kernel creates ahead of time land here.
CREATE TABLE astra_doc_txn_default PARTITION OF astra_doc_txn DEFAULT;

CREATE INDEX astra_doc_txn_list_idx    ON astra_doc_txn (tenant_id, doctype, modified_at DESC);
CREATE INDEX astra_doc_txn_created_idx ON astra_doc_txn (tenant_id, doctype, created_at DESC);

ALTER TABLE astra_doc_txn ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_doc_txn FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_doc_txn_tenant_isolation ON astra_doc_txn
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));

ALTER TABLE astra_doc_txn_default ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_doc_txn_default FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_doc_txn_default_tenant ON astra_doc_txn_default
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));

-- Creates the partition for the month holding `d`, with month boundaries in the business time zone `tz`
-- (a day of orders in Vietnam starts at 00:00+07, not at 00:00 UTC), and row-level security on the partition
-- itself so that querying a partition directly is as isolated as querying the parent. Idempotent.
CREATE FUNCTION astra_doc_txn_ensure_partition(d date, tz text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE
    first_day date := date_trunc('month', d)::date;
    part text := 'astra_doc_txn_' || to_char(first_day, 'YYYY_MM');
BEGIN
    IF to_regclass(part) IS NULL THEN
        EXECUTE format('CREATE TABLE %I PARTITION OF astra_doc_txn FOR VALUES FROM (%L) TO (%L)', part,
                       (first_day::timestamp AT TIME ZONE tz), ((first_day + interval '1 month')::timestamp AT TIME ZONE tz));
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', part);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', part);
        EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = current_setting(''astra.tenant'', true)) '
                       || 'WITH CHECK (tenant_id = current_setting(''astra.tenant'', true))', part || '_tenant', part);
    END IF;
    RETURN part;
END $$;

-- 2. Materialised projections (projected: materialised): one typed column per field and table, computed from
--    data by a trigger the kernel generates from this registry, then backfilled. Every kernel instance generates
--    the trigger from the whole registry, so instances that declare different fields agree on it.
CREATE TABLE astra_materialised (
    table_name    text        NOT NULL,
    column_name   text        NOT NULL,
    field         text        NOT NULL,
    sql_type      text        NOT NULL,
    expression    text        NOT NULL,   -- computes the value from $DATA$, the row's data
    declared_at   timestamptz NOT NULL DEFAULT now(),
    backfilled_at timestamptz,
    PRIMARY KEY (table_name, column_name)
);
