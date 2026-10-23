-- DocType JSONB benchmark: size report for the three shapes. Run as the bootstrap superuser at any point
-- (after 03-indexes.sql for the baseline, after 04-fixes.sql / 05-bigint-amounts.sql for the post-fix state).
-- Partitions of astra_doc_part are summed into one line. pgstattuple_approx gives the average live tuple
-- length and the free-space percentage (bloat) without a full scan.
\set ON_ERROR_STOP on
\pset footer off

SELECT relname,
       pg_size_pretty(pg_table_size(c.oid))          AS heap_toast,
       pg_size_pretty(pg_indexes_size(c.oid))        AS indexes,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
       reltuples::bigint                             AS rows
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname IN ('astra_doc', 'orders_rel', 'gen')
UNION ALL
SELECT 'astra_doc_part (all partitions)',
       pg_size_pretty(sum(pg_table_size(p.inhrelid))),
       pg_size_pretty(sum(pg_indexes_size(p.inhrelid))),
       pg_size_pretty(sum(pg_total_relation_size(p.inhrelid))),
       sum(c.reltuples)::bigint
FROM pg_inherits p JOIN pg_class c ON c.oid = p.inhrelid
WHERE p.inhparent = to_regclass('astra_doc_part') HAVING count(*) > 0
ORDER BY 1;

SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE relname IN ('astra_doc', 'orders_rel')
ORDER BY relname, indexrelname;

SELECT relname, pg_size_pretty(coalesce(pg_relation_size(reltoastrelid), 0)) AS toast
FROM pg_class WHERE relname IN ('astra_doc', 'orders_rel') ORDER BY 1;

SELECT 'astra_doc' AS relname, round(approx_tuple_len::numeric / greatest(approx_tuple_count, 1)) AS avg_row_bytes,
       round(approx_free_percent::numeric, 1) AS free_pct, dead_tuple_count
FROM pgstattuple_approx('astra_doc')
UNION ALL
SELECT 'orders_rel', round(approx_tuple_len::numeric / greatest(approx_tuple_count, 1)), round(approx_free_percent::numeric, 1), dead_tuple_count
FROM pgstattuple_approx('orders_rel');

SELECT round(avg(pg_column_size(data))) AS avg_json_bytes FROM astra_doc WHERE doctype = 'CustomerOrder';
