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

-- BMS module schema for Seata AT branches (deploy/db/10-bms.sql); used only by the Seata test profile.
CREATE ROLE astra_bms LOGIN PASSWORD 'astra_bms' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE astra TO astra_bms;
CREATE SCHEMA bms AUTHORIZATION astra_bms;
