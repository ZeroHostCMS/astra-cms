-- Logical replication for the Rust CDC worker (astra-cdc-rust). Idempotent; run as the superuser.
-- Requires wal_level = logical (set on the PostgreSQL command line in both stacks). The publication and
-- grants need the kernel's tables, so on a fresh database this step completes on its second run,
-- after the kernel has migrated the schema (scripts/k3d-up.sh runs it before and after).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'astra_cdc') THEN
        -- REPLICATION reads the WAL through a slot; no SUPERUSER and no BYPASSRLS: replica rows are
        -- written under row-level security like any other.
        CREATE ROLE astra_cdc LOGIN REPLICATION PASSWORD 'astra_cdc' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
    IF to_regclass('public.astra_doc') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'astra_cdc') THEN
        CREATE PUBLICATION astra_cdc FOR TABLE astra_doc WITH (publish = 'insert, update, delete');
    END IF;
    -- DocTypes with `partition: month` live in astra_doc_txn; publish its changes under the parent table's name,
    -- not under each monthly partition, so the worker sees one relation whatever the month.
    IF to_regclass('public.astra_doc_txn') IS NOT NULL
       AND EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'astra_cdc')
       AND NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'astra_cdc' AND tablename = 'astra_doc_txn') THEN
        ALTER PUBLICATION astra_cdc ADD TABLE astra_doc_txn;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'astra_cdc' AND NOT pubviaroot) THEN
        ALTER PUBLICATION astra_cdc SET (publish = 'insert, update, delete', publish_via_partition_root = true);
    END IF;
    IF to_regclass('public.replica_waybill') IS NOT NULL THEN
        GRANT USAGE ON SCHEMA public TO astra_cdc;
        GRANT SELECT, INSERT, UPDATE, DELETE ON replica_waybill, replica_order TO astra_cdc;
        GRANT SELECT, INSERT, UPDATE ON _cdc_sync_watermark TO astra_cdc;
    END IF;
END $$;
GRANT CONNECT ON DATABASE astra TO astra_cdc;
