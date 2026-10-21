-- BMS module schema for Seata AT branches (same as deploy/db/10-bms.sql, for a fresh volume).
CREATE ROLE astra_bms LOGIN PASSWORD 'astra_bms' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE astra TO astra_bms;
CREATE SCHEMA bms AUTHORIZATION astra_bms;
