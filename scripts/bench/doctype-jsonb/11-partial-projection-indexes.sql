-- DocType JSONB benchmark: projection indexes as ProjectionIndexer builds them after this change, partial on
-- "field IS NOT NULL". Documents of DocTypes without the field (Waybill has no customer or zone, Customer has
-- no ops_status) get no entry, so the index is smaller and writing those documents does not touch it.
-- Run as the bootstrap superuser after 10-limits-setup.sql.
\set ON_ERROR_STOP on
\timing on
SET ROLE astra_app;
DROP INDEX IF EXISTS astra_doc_pf_customer;
DROP INDEX IF EXISTS astra_doc_pf_zone;
DROP INDEX IF EXISTS astra_doc_pf_ops_status;
DROP INDEX IF EXISTS astra_doc_m_cod_amount;   -- the kernel has no materialised column (yet)
CREATE INDEX astra_doc_pf_customer   ON astra_doc (tenant_id, doctype, astra_field(data, 'customer'), modified_at DESC)   WHERE astra_field(data, 'customer') IS NOT NULL;
CREATE INDEX astra_doc_pf_zone       ON astra_doc (tenant_id, doctype, astra_field(data, 'zone'), modified_at DESC)       WHERE astra_field(data, 'zone') IS NOT NULL;
CREATE INDEX astra_doc_pf_ops_status ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC) WHERE astra_field(data, 'ops_status') IS NOT NULL;
DROP INDEX IF EXISTS orders_rel_cod_amount;
RESET ROLE;
VACUUM ANALYZE astra_doc;
VACUUM ANALYZE orders_rel;
