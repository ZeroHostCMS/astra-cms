-- DocumentStore.update of the wide CustomerOrder document (status kept on the order): get, Link check,
-- UPDATE with the optimistic version check, RETURNING *.
\set i random(1, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND name = (SELECT name FROM bench_orders WHERE i = :i) \gset g_
SELECT 1 FROM astra_doc WHERE doctype = 'Customer' AND name = (SELECT customer FROM bench_orders WHERE i = :i);
UPDATE astra_doc SET data = data || jsonb_build_object('ops_status', 'Delivered'), modified_at = now(), version = version + 1 WHERE doctype = 'CustomerOrder' AND name = (SELECT name FROM bench_orders WHERE i = :i) AND version = :g_version RETURNING *;
END;
