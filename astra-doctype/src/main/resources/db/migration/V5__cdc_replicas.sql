-- Read replicas maintained by the Rust CDC worker (astra-cdc-rust) from the WAL of astra_doc, so that
-- customer care reads parcels and orders without querying the operational documents. Rows are written
-- under the same row-level security as everything else: the worker binds each row's tenant first.

CREATE TABLE replica_waybill (
    tenant_id     text        NOT NULL,
    name          text        NOT NULL,
    order_name    text,
    ops_status    text,
    current_hub   text,
    receiver_name text,
    scans         integer,
    source_lsn    pg_lsn      NOT NULL,   -- WAL position of the change this row reflects
    synced_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, name)
);

CREATE TABLE replica_order (
    tenant_id     text           NOT NULL,
    name          text           NOT NULL,
    customer      text,
    docstatus     integer,
    zone          text,
    cod_amount    numeric(18, 2),
    shipping_fee  numeric(18, 2),
    waybill       text,
    source_lsn    pg_lsn         NOT NULL,
    synced_at     timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, name)
);

ALTER TABLE replica_waybill ENABLE ROW LEVEL SECURITY;
ALTER TABLE replica_waybill FORCE ROW LEVEL SECURITY;
CREATE POLICY replica_waybill_tenant ON replica_waybill
    USING (tenant_id = current_setting('astra.tenant', true)) WITH CHECK (tenant_id = current_setting('astra.tenant', true));

ALTER TABLE replica_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE replica_order FORCE ROW LEVEL SECURITY;
CREATE POLICY replica_order_tenant ON replica_order
    USING (tenant_id = current_setting('astra.tenant', true)) WITH CHECK (tenant_id = current_setting('astra.tenant', true));

-- How far the worker has applied the WAL. Everything committed at or before last_lsn is in the replicas.
CREATE TABLE _cdc_sync_watermark (
    slot        text        PRIMARY KEY,
    last_lsn    pg_lsn      NOT NULL,
    events      bigint      NOT NULL DEFAULT 0,
    lag_ms      integer,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
