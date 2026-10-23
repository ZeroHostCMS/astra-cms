-- DocType JSONB benchmark: the plans behind the partition-mode numbers. Lookups by name with and without the
-- name-date lower bound (partitions probed), a customer list on 18 partitions, and a COD range filter planned
-- generically (a server-prepared statement after a few executions) versus with its value (what the kernel does).
-- Run as the bootstrap superuser after 12-partition-setup.sql. Output only, no changes.
\set ON_ERROR_STOP on
\pset footer off
SET ROLE astra_app;
SELECT set_config('astra.tenant', 'hanoi-express', false) \gset
SELECT name, waybill, i FROM bench_orders WHERE i = (SELECT count(*) / 2 FROM bench_orders) \gset

\echo '=== get by name on 18 partitions, no bound: every partition probed'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND name = :'name';
\echo '=== get by name with the name-date lower bound: older months pruned'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND name = :'name'
    AND created_at >= timestamptz '2025-04-30 00:00+07' + (:i % 540) * interval '1 day';
\echo '=== get by name on the unpartitioned table'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND name = :'name';
\echo '=== 20 newest orders of a customer, 18 partitions'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder'
    AND astra_field(data, 'customer') = 'CUST-0123456' ORDER BY modified_at DESC, name DESC LIMIT 21;

PREPARE cod(bigint) AS SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND m_cod_amount > $1 ORDER BY modified_at DESC, name DESC LIMIT 21;
SET plan_cache_mode = force_generic_plan;
\echo '=== COD range, generic plan (server-prepared statement, value unknown when planned)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE cod(499000000);
SET plan_cache_mode = force_custom_plan;
\echo '=== COD range, planned with the value (unnamed statement, the kernel sets prepareThreshold 0 for range lists)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE cod(499000000);
RESET plan_cache_mode;
DEALLOCATE cod;
RESET ROLE;
