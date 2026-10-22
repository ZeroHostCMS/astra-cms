-- DocType JSONB benchmark: schema. Run as the bootstrap superuser against an empty database.
-- Three copies of the same CustomerOrder data:
--   astra_doc       exactly the kernel's table (V1 + V2 migrations + projection indexes)
--   orders_rel      the "traditional" hand-declared table with typed columns and B-tree indexes
--   astra_doc_part  the kernel's table partitioned by created_at (one partition per day)
-- Everything is owned by the non-superuser application role and protected by the same RLS policy
-- the kernel uses, so the planner sees exactly what production sees.

\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pgstattuple;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'astra_app') THEN
    CREATE ROLE astra_app LOGIN PASSWORD 'astra_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END $$;
GRANT CONNECT ON DATABASE bench TO astra_app;
GRANT USAGE, CREATE ON SCHEMA public TO astra_app;

SET ROLE astra_app;

-- ---------------------------------------------------------------- astra_doc (kernel, V1 + V2)
CREATE TABLE astra_doc (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
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
    CONSTRAINT astra_doc_tenant_doctype_name_key UNIQUE (tenant_id, doctype, name)
);
ALTER TABLE astra_doc ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_doc FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_doc_tenant_isolation ON astra_doc
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));

-- ---------------------------------------------------------------- orders_rel (traditional)
CREATE TABLE orders_rel (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        text        NOT NULL,
    name             text        NOT NULL,
    docstatus        smallint    NOT NULL DEFAULT 0,
    owner            text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    modified_at      timestamptz NOT NULL DEFAULT now(),
    version          integer     NOT NULL DEFAULT 1,
    customer         text        NOT NULL,
    receiver_name    text        NOT NULL,
    receiver_phone   text        NOT NULL,
    receiver_address text        NOT NULL,
    zone             text        NOT NULL,
    service          text        NOT NULL,
    weight_kg        real        NOT NULL,
    cod_amount       numeric(18,2) NOT NULL DEFAULT 0,
    shipping_fee     numeric(18,2),
    priced_by        text,
    waybill          text,
    ops_status       text        NOT NULL,
    note             text,
    CONSTRAINT orders_rel_tenant_name_key UNIQUE (tenant_id, name)
);
ALTER TABLE orders_rel ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders_rel FORCE ROW LEVEL SECURITY;
CREATE POLICY orders_rel_tenant_isolation ON orders_rel
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));

-- ---------------------------------------------------------------- astra_doc_part (kernel table, daily range partitions)
-- A unique constraint on a partitioned table must include the partition key, so the kernel's
-- (tenant_id, doctype, name) uniqueness becomes (tenant_id, doctype, name, created_at).
CREATE TABLE astra_doc_part (
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
    CONSTRAINT astra_doc_part_tenant_doctype_name_key UNIQUE (tenant_id, doctype, name, created_at)
) PARTITION BY RANGE (created_at);
ALTER TABLE astra_doc_part ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_doc_part FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_doc_part_tenant_isolation ON astra_doc_part
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));
DO $$
DECLARE d date;
BEGIN
  FOR d IN SELECT generate_series(date '2026-10-18', date '2026-10-24', interval '1 day') LOOP
    EXECUTE format('CREATE TABLE astra_doc_part_%s PARTITION OF astra_doc_part FOR VALUES FROM (%L) TO (%L)',
                   to_char(d, 'YYYY_MM_DD'), d, d + 1);
  END LOOP;
END $$;

RESET ROLE;
