-- Leakproof accessor for projected DocType fields. Idempotent; run as the superuser (scripts/db-setup.sh).
--
-- Under row-level security PostgreSQL only uses an index for a predicate when every function in it is
-- LEAKPROOF. The ->> operator (jsonb_object_field_text) is not marked leakproof, so an index on
-- (data->>'field') is never used for the kernel's list filters and every filter scans the whole tenant.
-- astra_field is an alias of the same C function, declared LEAKPROOF; it does not raise errors that reveal
-- row contents. Declaring a function LEAKPROOF requires a superuser, so the kernel cannot create it in a
-- Flyway migration; RowLevelSecurityGuard refuses to start without it.
--
-- It lives in its own schema, owned by the superuser: the application role may call it but not replace it,
-- and Flyway, which requires an empty "public" schema on a new database, does not see it.
CREATE SCHEMA IF NOT EXISTS astra_ext;
GRANT USAGE ON SCHEMA astra_ext TO astra_app;
CREATE OR REPLACE FUNCTION astra_ext.astra_field(jsonb, text) RETURNS text
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

