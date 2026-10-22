-- DocType JSONB benchmark: indexes, built after the load so each build time is measured on 5M rows.
--   astra_doc:      exactly what V1 + ProjectionIndexer create (list index, GIN, projection on customer / zone)
--                   plus the projection ops_status carries in the Waybill DocType.
--   orders_rel:     the equivalent hand-declared B-tree indexes.
--   astra_doc_part: the same indexes as astra_doc, which PostgreSQL creates on every partition.
\set ON_ERROR_STOP on
\timing on
SET ROLE astra_app;

CREATE INDEX astra_doc_list_idx ON astra_doc (tenant_id, doctype, modified_at DESC);
CREATE INDEX astra_doc_data_gin ON astra_doc USING gin (data jsonb_path_ops);
CREATE INDEX astra_doc_p_customer   ON astra_doc (tenant_id, doctype, (data->>'customer'));
CREATE INDEX astra_doc_p_zone       ON astra_doc (tenant_id, doctype, (data->>'zone'));
CREATE INDEX astra_doc_p_ops_status ON astra_doc (tenant_id, doctype, (data->>'ops_status'));

CREATE INDEX orders_rel_list_idx   ON orders_rel (tenant_id, modified_at DESC);
CREATE INDEX orders_rel_customer   ON orders_rel (tenant_id, customer);
CREATE INDEX orders_rel_zone       ON orders_rel (tenant_id, zone);
CREATE INDEX orders_rel_ops_status ON orders_rel (tenant_id, ops_status);

CREATE INDEX astra_doc_part_list_idx   ON astra_doc_part (tenant_id, doctype, modified_at DESC);
CREATE INDEX astra_doc_part_p_customer ON astra_doc_part (tenant_id, doctype, (data->>'customer'));
CREATE INDEX astra_doc_part_p_zone     ON astra_doc_part (tenant_id, doctype, (data->>'zone'));
CREATE INDEX astra_doc_part_p_ops      ON astra_doc_part (tenant_id, doctype, (data->>'ops_status'));

VACUUM ANALYZE astra_doc;
VACUUM ANALYZE orders_rel;
VACUUM ANALYZE astra_doc_part;
RESET ROLE;
