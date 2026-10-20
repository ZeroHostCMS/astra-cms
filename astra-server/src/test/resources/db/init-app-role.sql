-- Test copy of deploy/docker/init/01-app-role.sql for Quarkus Dev Services (init-script-path).
-- Keep in sync: the application must connect as a non-superuser for row-level security to apply.
CREATE ROLE astra_app LOGIN PASSWORD 'astra_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE astra TO astra_app;
GRANT USAGE, CREATE ON SCHEMA public TO astra_app;
