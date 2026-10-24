-- A scan event through the REST resource before this change: DocResource.update read the document in one
-- transaction to check write permission, then DocumentStore.update opened a second transaction, read it
-- again, checked Links and wrote. Compare with jsonb-update-waybill.sql (one transaction).
\set i random(1, :norders)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'Waybill' AND name = (SELECT waybill FROM bench_orders WHERE i = :i);
END;
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'Waybill' AND name = (SELECT waybill FROM bench_orders WHERE i = :i) \gset g_
SELECT 1 FROM astra_doc WHERE doctype = 'CustomerOrder' AND name = (SELECT name FROM bench_orders WHERE i = :i);
UPDATE astra_doc SET data = jsonb_set(data || jsonb_build_object('ops_status', 'Out for Delivery', 'current_hub', 'HN-12'), '{scan_log}', coalesce(data->'scan_log', '[]'::jsonb) || jsonb_build_array(jsonb_build_object('at', now(), 'status', 'Out for Delivery', 'hub', 'HN-12', 'by', 'courier' || :client_id))), modified_at = now(), version = version + 1 WHERE doctype = 'Waybill' AND name = (SELECT waybill FROM bench_orders WHERE i = :i) AND version = :g_version RETURNING *;
END;
