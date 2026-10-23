-- DocType JSONB benchmark: does an index on an amount column serve a selective range predicate under
-- row-level security? numeric_gt is not leakproof, int8gt is. Same data in both columns, same index shape,
-- a range that matches 0.1 % of rows. Run as the bootstrap superuser; self-contained, cleans up after itself.
\set ON_ERROR_STOP on
\pset footer off
DROP TABLE IF EXISTS lp_amount;
SELECT proname, proleakproof FROM pg_proc WHERE proname IN ('numeric_gt', 'int8gt') ORDER BY 1;

SET ROLE astra_app;
CREATE TABLE lp_amount (tenant_id text NOT NULL, amount_num numeric(18,2) NOT NULL, amount_i8 bigint NOT NULL);
INSERT INTO lp_amount SELECT 'hanoi-express', a, a FROM (SELECT (random() * 5000000)::bigint AS a FROM generate_series(1, 1000000)) s;
ALTER TABLE lp_amount ENABLE ROW LEVEL SECURITY;
ALTER TABLE lp_amount FORCE ROW LEVEL SECURITY;
CREATE POLICY p ON lp_amount USING (tenant_id = current_setting('astra.tenant', true));
CREATE INDEX lp_amount_num ON lp_amount (tenant_id, amount_num);
CREATE INDEX lp_amount_i8  ON lp_amount (tenant_id, amount_i8);
ANALYZE lp_amount;
SELECT set_config('astra.tenant', 'hanoi-express', false);

\echo '--- numeric column, under RLS'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) SELECT count(*) FROM lp_amount WHERE amount_num > 4995000;
\echo '--- bigint column, under RLS'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) SELECT count(*) FROM lp_amount WHERE amount_i8 > 4995000;
RESET ROLE;
\echo '--- numeric column, superuser (no RLS)'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) SELECT count(*) FROM lp_amount WHERE tenant_id = 'hanoi-express' AND amount_num > 4995000;
DROP TABLE lp_amount;
