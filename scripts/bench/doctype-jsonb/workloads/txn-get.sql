-- jsonb-get.sql on astra_doc_txn (partition: month, 18 monthly partitions): a lookup by name probes every partition.
\set i random(1, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND name = (SELECT name FROM bench_orders WHERE i = :i);
END;
