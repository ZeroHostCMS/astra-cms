-- BMS module schema for Seata AT branches, owned by its own role. Idempotent; run as the superuser.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'astra_bms') THEN
        CREATE ROLE astra_bms LOGIN PASSWORD 'astra_bms' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END $$;
GRANT CONNECT ON DATABASE astra TO astra_bms;
CREATE SCHEMA IF NOT EXISTS bms AUTHORIZATION astra_bms;
