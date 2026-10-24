-- Test copy of deploy/docker/init/01-app-role.sql for Quarkus Dev Services (init-script-path).
-- Keep in sync: the application must connect as a non-superuser for row-level security to apply.
CREATE ROLE astra_app LOGIN PASSWORD 'astra_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE astra TO astra_app;
GRANT USAGE, CREATE ON SCHEMA public TO astra_app;

-- Leakproof accessor for projected fields (deploy/db/05-astra-field.sql); declaring LEAKPROOF needs a superuser.
CREATE SCHEMA astra_ext;   -- own schema: Flyway requires an empty "public" on a new database
GRANT USAGE ON SCHEMA astra_ext TO astra_app;
CREATE FUNCTION astra_ext.astra_field(jsonb, text) RETURNS text
    LANGUAGE internal IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'jsonb_object_field_text';
GRANT EXECUTE ON FUNCTION astra_ext.astra_field(jsonb, text) TO astra_app;

-- Tenants that have documents, for the kernel's storage maintenance (backfilling a materialised column, moving
-- documents into the month-partitioned table). Row-level security hides other tenants from astra_app, so this
-- runs as its owner, the superuser; it returns tenant identifiers only. Skip scan over the leading tenant_id of
-- the unique (tenant_id, doctype, name) index: one index probe per tenant.
CREATE OR REPLACE FUNCTION astra_ext.tenants() RETURNS SETOF text
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $fn$
DECLARE tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['public.astra_doc', 'public.astra_doc_txn'] LOOP
        IF to_regclass(tbl) IS NOT NULL THEN
            RETURN QUERY EXECUTE format(
                'WITH RECURSIVE s(t) AS ((SELECT tenant_id FROM %s ORDER BY tenant_id LIMIT 1) UNION ALL '
                || 'SELECT (SELECT tenant_id FROM %s WHERE tenant_id > s.t ORDER BY tenant_id LIMIT 1) FROM s WHERE s.t IS NOT NULL) '
                || 'SELECT t FROM s WHERE t IS NOT NULL', tbl, tbl);
        END IF;
    END LOOP;
END $fn$;
REVOKE EXECUTE ON FUNCTION astra_ext.tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION astra_ext.tenants() TO astra_app;

-- BMS module schema for Seata AT branches (deploy/db/10-bms.sql); used only by the Seata test profile.
CREATE ROLE astra_bms LOGIN PASSWORD 'astra_bms' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE astra TO astra_bms;
CREATE SCHEMA bms AUTHORIZATION astra_bms;
