-- Runs once at database initialisation (docker-entrypoint-initdb.d), as the bootstrap superuser.
-- The kernel never connects as a superuser: superusers and BYPASSRLS roles ignore row-level
-- security, which would silently disable tenant isolation. The application role below owns the
-- schema objects Flyway creates; FORCE ROW LEVEL SECURITY keeps the owner subject to policies.
CREATE ROLE astra_app LOGIN PASSWORD 'astra_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE astra TO astra_app;
GRANT USAGE, CREATE ON SCHEMA public TO astra_app;

-- Leakproof accessor the kernel filters and indexes projected fields with (deploy/db/05-astra-field.sql).
-- Declaring a function LEAKPROOF needs a superuser, which is why it is created here and not by Flyway.
-- Its own schema: Flyway requires an empty "public" schema on a new database.
CREATE SCHEMA astra_ext;
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
