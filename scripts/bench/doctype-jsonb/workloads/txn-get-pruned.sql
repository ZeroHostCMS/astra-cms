-- txn-get.sql with the lower bound the kernel adds for a dated series name (created_at >= the name's day - 1):
-- runtime partition pruning skips every older month. In this data set the k-th order was created on day k % 540.
\set i random(1, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND name = (SELECT name FROM bench_orders WHERE i = :i) AND created_at >= timestamptz '2025-04-30 00:00+07' + (:i % 540) * interval '1 day';
END;
