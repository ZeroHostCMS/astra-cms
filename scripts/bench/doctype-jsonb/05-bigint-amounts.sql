-- DocType JSONB benchmark: amounts as bigint. numeric comparison operators are not leakproof, so under
-- row-level security an index on a numeric column is never used for a range predicate, on the JSONB
-- table and on the traditional table alike. int8 comparisons are leakproof. VND has no decimals, so a
-- Currency projection can be materialised as bigint. ALTER TYPE rewrites the table, which also removes
-- the bloat left by the backfill in 04-fixes.sql (a production rollout would add a new bigint column,
-- backfill in batches and repack instead).
\set ON_ERROR_STOP on
\timing on
SET ROLE astra_app;
ALTER TABLE astra_doc  ALTER COLUMN m_cod_amount TYPE bigint USING m_cod_amount::bigint;
ALTER TABLE orders_rel ALTER COLUMN cod_amount   TYPE bigint USING cod_amount::bigint;
VACUUM ANALYZE astra_doc;
VACUUM ANALYZE orders_rel;
RESET ROLE;
