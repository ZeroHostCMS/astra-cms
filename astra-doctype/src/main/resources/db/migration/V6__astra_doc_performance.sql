-- Storage tuning from the DocType JSONB benchmark (docs/benchmark-doctype-jsonb-the-right-way.md).
--
-- 1. The GIN index on data is never used: jsonb @> is not LEAKPROOF, so under row-level security the planner
--    cannot use it, while every insert and update pays for it (about 15 entries per document, 1 GB per
--    6 million documents, insert throughput about a fifth lower). Filters go through projection indexes.
DROP INDEX IF EXISTS astra_doc_data_gin;

-- 2. Every update of a document writes a new row version and leaves a dead one, and HOT updates are
--    impossible because modified_at is indexed. At millions of status changes a day the default trigger
--    (20 % of the table) lets dead rows pile up for days on a large table; vacuum at 1 %, without throttling.
ALTER TABLE astra_doc SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_vacuum_cost_delay = 1,
    autovacuum_vacuum_insert_scale_factor = 0.05
);
