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
