-- DocType JSONB benchmark: lean reload used for the post-fix measurements (Q5/Q7 with bigint amounts and the
-- concurrent read mix). Same generator and row count as 02-load.sql, but only the two tables and the indexes
-- the fixed queries need, no GIN, no partitions, and the materialised amount written at load time (the state
-- a table is in after backfill + repack). Run as the bootstrap superuser on an empty database after 01-schema.sql.
\set ON_ERROR_STOP on
\timing on
\i 02-load.sql
SET ROLE astra_app;
SELECT set_config('astra.tenant', 'hanoi-express', false);
DROP TABLE astra_doc_part;
ALTER TABLE astra_doc ADD COLUMN m_cod_amount bigint;
UPDATE astra_doc SET m_cod_amount = (data->>'cod_amount')::numeric::bigint WHERE doctype = 'CustomerOrder';
VACUUM FULL astra_doc;
ALTER TABLE orders_rel ALTER COLUMN cod_amount TYPE bigint USING cod_amount::bigint;
RESET ROLE;
CREATE FUNCTION astra_field(jsonb, text) RETURNS text
    LANGUAGE internal IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'jsonb_object_field_text';
GRANT EXECUTE ON FUNCTION astra_field(jsonb, text) TO astra_app;
SET ROLE astra_app;
CREATE INDEX astra_doc_list_idx          ON astra_doc (tenant_id, doctype, modified_at DESC);
CREATE INDEX astra_doc_lp_customer       ON astra_doc (tenant_id, doctype, astra_field(data, 'customer'));
CREATE INDEX astra_doc_lp_zone           ON astra_doc (tenant_id, doctype, astra_field(data, 'zone'));
CREATE INDEX astra_doc_lp_ops_status_mod ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC);
CREATE INDEX astra_doc_created_idx       ON astra_doc (tenant_id, doctype, created_at DESC);
CREATE INDEX astra_doc_m_cod_amount      ON astra_doc (tenant_id, doctype, m_cod_amount);
CREATE INDEX orders_rel_list_idx   ON orders_rel (tenant_id, modified_at DESC);
CREATE INDEX orders_rel_customer   ON orders_rel (tenant_id, customer);
CREATE INDEX orders_rel_zone       ON orders_rel (tenant_id, zone);
CREATE INDEX orders_rel_ops_status ON orders_rel (tenant_id, ops_status, modified_at DESC);
CREATE INDEX orders_rel_created_idx ON orders_rel (tenant_id, created_at DESC);
CREATE INDEX orders_rel_cod_amount  ON orders_rel (tenant_id, cod_amount);
DROP TABLE gen CASCADE;
VACUUM ANALYZE astra_doc;
VACUUM ANALYZE orders_rel;
RESET ROLE;
