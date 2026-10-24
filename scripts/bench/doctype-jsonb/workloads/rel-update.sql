-- Status change on the traditional table: get, Link check, UPDATE with version check, RETURNING *.
\set i random(1, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM orders_rel WHERE name = (SELECT name FROM bench_orders WHERE i = :i) \gset g_
SELECT 1 FROM astra_doc WHERE doctype = 'Customer' AND name = (SELECT customer FROM bench_orders WHERE i = :i);
UPDATE orders_rel SET ops_status = 'Out for Delivery', modified_at = now(), version = version + 1 WHERE name = (SELECT name FROM bench_orders WHERE i = :i) AND version = :g_version RETURNING *;
END;
