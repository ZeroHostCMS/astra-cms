-- Zero-DDL document storage: one table for every DocType, fields in JSONB.
-- Tenant isolation is enforced by PostgreSQL row-level security on every statement,
-- keyed on a transaction-local setting the kernel binds from the ScopedValue tenant.

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
    CONSTRAINT astra_doc_tenant_doctype_name_key UNIQUE (tenant_id, doctype, name)
);

CREATE INDEX astra_doc_list_idx ON astra_doc (tenant_id, doctype, modified_at DESC);
CREATE INDEX astra_doc_data_gin ON astra_doc USING gin (data jsonb_path_ops);

ALTER TABLE astra_doc ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_doc FORCE ROW LEVEL SECURITY;   -- applies to the table owner too

CREATE POLICY astra_doc_tenant_isolation ON astra_doc
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));

-- Cluster-safe naming series, gap-free inside the enclosing transaction.
CREATE TABLE astra_series (
    tenant_id  text    NOT NULL,
    series_key text    NOT NULL,
    current    bigint  NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, series_key)
);

ALTER TABLE astra_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_series FORCE ROW LEVEL SECURITY;

CREATE POLICY astra_series_tenant_isolation ON astra_series
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));
