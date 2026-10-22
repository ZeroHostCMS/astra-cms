-- Ledger storage: fixed relational columns, append-only, partitioned by posting month.
-- Corrections are reversal entries, never UPDATE or DELETE; a trigger enforces this for every role,
-- including the table owner. Tenant isolation uses the same row-level security setting as astra_doc.

CREATE TABLE astra_ledger (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id     text           NOT NULL,
    ledger        text           NOT NULL DEFAULT 'GL',
    posting_date  date           NOT NULL,
    account       text           NOT NULL,
    party         text,
    debit         numeric(18, 2) NOT NULL DEFAULT 0,
    credit        numeric(18, 2) NOT NULL DEFAULT 0,
    voucher_type  text           NOT NULL,
    voucher_no    text           NOT NULL,
    is_reversal   boolean        NOT NULL DEFAULT false,
    remarks       text,
    posted_by     text           NOT NULL,
    posted_at     timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (id, posting_date),
    CONSTRAINT astra_ledger_one_side CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0))
) PARTITION BY RANGE (posting_date);

-- Rows outside the monthly partitions that the kernel creates ahead of time land here.
CREATE TABLE astra_ledger_default PARTITION OF astra_ledger DEFAULT;

CREATE INDEX astra_ledger_voucher_idx ON astra_ledger (tenant_id, voucher_type, voucher_no);
CREATE INDEX astra_ledger_account_idx ON astra_ledger (tenant_id, account, posting_date);

CREATE FUNCTION astra_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'astra_ledger is append-only: post a reversal instead of %', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER astra_ledger_no_update_delete
    BEFORE UPDATE OR DELETE ON astra_ledger
    FOR EACH ROW EXECUTE FUNCTION astra_ledger_append_only();

-- Creates the monthly partition holding `d`, with row-level security on the partition itself so that
-- querying a partition directly is as isolated as querying the parent.
CREATE FUNCTION astra_ledger_ensure_partition(d date) RETURNS text LANGUAGE plpgsql AS $$
DECLARE
    first_day date := date_trunc('month', d)::date;
    part text := 'astra_ledger_' || to_char(first_day, 'YYYY_MM');
BEGIN
    IF to_regclass(part) IS NULL THEN
        EXECUTE format('CREATE TABLE %I PARTITION OF astra_ledger FOR VALUES FROM (%L) TO (%L)',
                       part, first_day, (first_day + interval '1 month')::date);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', part);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', part);
        EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = current_setting(''astra.tenant'', true)) '
                       || 'WITH CHECK (tenant_id = current_setting(''astra.tenant'', true))', part || '_tenant', part);
    END IF;
    RETURN part;
END $$;

ALTER TABLE astra_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_ledger_tenant_isolation ON astra_ledger
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));

ALTER TABLE astra_ledger_default ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_ledger_default FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_ledger_default_tenant ON astra_ledger_default
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));
