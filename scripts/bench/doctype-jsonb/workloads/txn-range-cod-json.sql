-- The same range filter read from the JSON document, what the kernel does until the column is backfilled
-- (or without materialising): no index can serve it under RLS.
\set x random(498000000, 499900000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND (CASE WHEN pg_input_is_valid(astra_field(data, 'cod_amount'), 'numeric') THEN round((astra_field(data, 'cod_amount'))::numeric * 100)::bigint END) > :x ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
