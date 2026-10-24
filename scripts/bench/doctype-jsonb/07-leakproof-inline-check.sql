-- DocType JSONB benchmark: does a plain SQL wrapper declared LEAKPROOF survive inlining?
-- Hypothesis (docs/benchmark-doctype-jsonb-the-right-way.md 4.2): the planner inlines a simple SQL function, then sees the
-- non-leakproof ->> again and refuses to use the index under row-level security; a LANGUAGE internal alias
-- is not inlined, so its LEAKPROOF label holds. Run as the bootstrap superuser after astra_field exists
-- (04-fixes.sql or 06-lean-reload.sql).
\set ON_ERROR_STOP on
\pset footer off

DROP TABLE IF EXISTS lp_test;
DROP FUNCTION IF EXISTS lp_sql(jsonb, text);
CREATE FUNCTION lp_sql(jsonb, text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'select $1->>$2';
GRANT EXECUTE ON FUNCTION lp_sql(jsonb, text) TO astra_app;

SELECT p.proname, l.lanname AS language, p.proleakproof
FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang WHERE p.proname IN ('lp_sql', 'astra_field', 'jsonb_object_field_text') ORDER BY 1;

SET ROLE astra_app;
CREATE TABLE lp_test (tenant_id text NOT NULL, data jsonb NOT NULL);
INSERT INTO lp_test SELECT 'hanoi-express', jsonb_build_object('k', 'v' || n) FROM generate_series(1, 200000) n;
ALTER TABLE lp_test ENABLE ROW LEVEL SECURITY;
ALTER TABLE lp_test FORCE ROW LEVEL SECURITY;
CREATE POLICY p ON lp_test USING (tenant_id = current_setting('astra.tenant', true));
CREATE INDEX lp_test_sql ON lp_test (tenant_id, lp_sql(data, 'k'));
CREATE INDEX lp_test_int ON lp_test (tenant_id, astra_field(data, 'k'));
ANALYZE lp_test;
SELECT set_config('astra.tenant', 'hanoi-express', false);

\echo '--- SQL wrapper declared LEAKPROOF, under RLS (Index Scan or Seq Scan + Filter?)'
EXPLAIN (COSTS OFF) SELECT * FROM lp_test WHERE lp_sql(data, 'k') = 'v5';
\echo '--- LANGUAGE internal alias, under RLS (expected: Index Scan)'
EXPLAIN (COSTS OFF) SELECT * FROM lp_test WHERE astra_field(data, 'k') = 'v5';
\echo '--- same two queries as superuser (bypasses the policy, no RLS)'
RESET ROLE;
\echo '--- SQL wrapper, superuser (no RLS)'
EXPLAIN (COSTS OFF) SELECT * FROM lp_test WHERE lp_sql(data, 'k') = 'v5';
\echo '--- internal alias, superuser (no RLS)'
EXPLAIN (COSTS OFF) SELECT * FROM lp_test WHERE astra_field(data, 'k') = 'v5';

DROP TABLE lp_test;
DROP FUNCTION lp_sql(jsonb, text);
