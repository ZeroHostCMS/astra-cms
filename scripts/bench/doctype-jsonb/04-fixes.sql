-- DocType JSONB benchmark: the corrections the baseline run motivates. Run as the bootstrap superuser.
--
-- F1  Leakproof projection accessor. Under row-level security the planner refuses to use a non-leakproof
--     expression as an index condition (it could leak rows the policy hides through error messages), and
--     jsonb_object_field_text (->>) is not leakproof. A LANGUAGE internal alias of the same C function,
--     declared LEAKPROOF by a superuser, is: indexes and queries built on it are usable under RLS.
-- F2  Composite projection index for low-cardinality fields: (tenant, doctype, field, modified_at DESC)
--     serves "filter by status, newest first, LIMIT 20" without a sort.
-- F3  Materialised column for a numeric field that is ranged over and aggregated (the design doc's
--     `projected: materialised`): ADD COLUMN is metadata-only, backfill runs in batches, then index.
-- F4  B-tree on created_at so the unpartitioned table can answer day-range lists.
\set ON_ERROR_STOP on
\timing on

CREATE FUNCTION astra_field(jsonb, text) RETURNS text
    LANGUAGE internal IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'jsonb_object_field_text';
GRANT EXECUTE ON FUNCTION astra_field(jsonb, text) TO astra_app;

SET ROLE astra_app;

-- F1
CREATE INDEX astra_doc_lp_customer ON astra_doc (tenant_id, doctype, astra_field(data, 'customer'));
-- F2
CREATE INDEX astra_doc_lp_ops_status_mod ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC);
-- F4
CREATE INDEX astra_doc_created_idx ON astra_doc (tenant_id, doctype, created_at DESC);
CREATE INDEX orders_rel_created_idx ON orders_rel (tenant_id, created_at DESC);

-- F3: materialised column, backfilled in 250k-row batches by name range (what a kernel backfill job does)
ALTER TABLE astra_doc ADD COLUMN m_cod_amount numeric(18,2);
SELECT set_config('astra.tenant', 'hanoi-express', false);
DO $$
DECLARE lo bigint := 1; step int := 250000;
BEGIN
  WHILE lo <= 5000000 LOOP
    UPDATE astra_doc SET m_cod_amount = (data->>'cod_amount')::numeric
     WHERE doctype = 'CustomerOrder' AND m_cod_amount IS NULL
       AND name >= 'ORD-' || to_char(timestamptz '2026-10-20 00:00+07' + (lo - 1) * (interval '60 hours' / 5000000), 'YYYYMMDD') || '-' || lpad(lo::text, 8, '0')
       AND name <= 'ORD-' || to_char(timestamptz '2026-10-20 00:00+07' + (lo + step - 2) * (interval '60 hours' / 5000000), 'YYYYMMDD') || '-' || lpad((lo + step - 1)::text, 8, '0');
    lo := lo + step;
  END LOOP;
END $$;
-- the name-range batches above span the day boundary imperfectly; finish whatever is left in one pass
UPDATE astra_doc SET m_cod_amount = (data->>'cod_amount')::numeric WHERE doctype = 'CustomerOrder' AND m_cod_amount IS NULL;
CREATE INDEX astra_doc_m_cod_amount ON astra_doc (tenant_id, doctype, m_cod_amount);
CREATE INDEX orders_rel_cod_amount  ON orders_rel (tenant_id, cod_amount);
VACUUM ANALYZE astra_doc;
VACUUM ANALYZE orders_rel;
RESET ROLE;
