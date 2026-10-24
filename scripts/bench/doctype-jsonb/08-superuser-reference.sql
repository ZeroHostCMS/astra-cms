-- DocType JSONB benchmark: the same Q2 and Q8b as run-queries.sh, executed by the bootstrap superuser, which
-- bypasses row-level security. Reference only: it shows that the expression index and the GIN are valid and
-- would be chosen if the policy were not in the way. Run after 03-indexes.sql (the GIN must still exist).
\set ON_ERROR_STOP on
\pset footer off
\echo '--- Q2 by superuser (no RLS): expression index on data->>customer'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM astra_doc WHERE tenant_id = 'hanoi-express' AND doctype = 'CustomerOrder' AND (data->>'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM astra_doc WHERE tenant_id = 'hanoi-express' AND doctype = 'CustomerOrder' AND (data->>'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0;
\echo '--- Q8b by superuser (no RLS): GIN jsonb_path_ops with @>'
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM astra_doc WHERE tenant_id = 'hanoi-express' AND doctype = 'CustomerOrder' AND data @> '{"zone":"Remote","ops_status":"Returned"}';
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM astra_doc WHERE tenant_id = 'hanoi-express' AND doctype = 'CustomerOrder' AND data @> '{"zone":"Remote","ops_status":"Returned"}';
