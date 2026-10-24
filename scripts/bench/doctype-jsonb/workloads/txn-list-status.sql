-- jsonb-list-status.sql on astra_doc_txn.
\set s random(1, 8)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND astra_field(data, 'ops_status') = (ARRAY['Created','Picked Up','At Hub','Out for Delivery','Delivered','Failed Delivery','Returned','Cancelled'])[:s] ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
