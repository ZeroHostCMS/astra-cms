-- Range filter on the materialised Currency column (cod_amount__gt), unpartitioned table: COD above a high
-- threshold (about 0.1 % of orders), 20 newest. bigint comparisons are leakproof, so the index serves it under RLS.
\set x random(498000000, 499900000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND m_cod_amount > :x ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
