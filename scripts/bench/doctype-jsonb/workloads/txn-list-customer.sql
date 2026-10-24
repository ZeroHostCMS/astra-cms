-- jsonb-list-customer.sql on astra_doc_txn: the customer index of every partition, merged by modified_at.
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc_txn WHERE doctype = 'CustomerOrder' AND astra_field(data, 'customer') = 'CUST-' || lpad(:c::text, 7, '0') ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
