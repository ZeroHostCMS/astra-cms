-- Point get on the traditional table.
\set i random(1, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM orders_rel WHERE name = (SELECT name FROM bench_orders WHERE i = :i);
END;
