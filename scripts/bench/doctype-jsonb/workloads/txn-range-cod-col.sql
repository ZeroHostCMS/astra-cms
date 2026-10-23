-- range-cod-col.sql on astra_doc_txn (the column's index in every partition).
\set x random(498000000, 499900000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND m_cod_amount > :x ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
