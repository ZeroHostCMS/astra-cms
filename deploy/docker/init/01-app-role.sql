-- Runs once at database initialisation (docker-entrypoint-initdb.d), as the bootstrap superuser.
-- The kernel never connects as a superuser: superusers and BYPASSRLS roles ignore row-level
-- security, which would silently disable tenant isolation. The application role below owns the
-- schema objects Flyway creates; FORCE ROW LEVEL SECURITY keeps the owner subject to policies.
CREATE ROLE astra_app LOGIN PASSWORD 'astra_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE astra TO astra_app;
GRANT USAGE, CREATE ON SCHEMA public TO astra_app;
