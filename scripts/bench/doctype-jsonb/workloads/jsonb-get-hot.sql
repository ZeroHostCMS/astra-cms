-- DocumentStore.get restricted to the newest 10 % of orders (bench_orders is ordered by name, which starts with
-- the order date): the access pattern of a live system, where orders in flight are read and old ones rarely.
\set i random(:hot_lo, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND name = (SELECT name FROM bench_orders WHERE i = :i);
END;
