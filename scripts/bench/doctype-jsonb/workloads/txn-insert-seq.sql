-- jsonb-insert-seq.sql into astra_doc_txn: the row goes to the current month; the materialise trigger fills m_cod_amount.
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT 1 FROM astra_doc WHERE doctype = 'Customer' AND name = 'CUST-' || lpad(:c::text, 7, '0');
SELECT nextval('bench_order_seq') AS current \gset
INSERT INTO astra_doc_txn (id, tenant_id, doctype, name, owner, data) VALUES (gen_random_uuid(), 'hanoi-express', 'CustomerOrder', 'BNS-20261023-' || lpad(:current::text, 8, '0'), 'user' || :client_id, jsonb_build_object('customer', 'CUST-' || lpad(:c::text, 7, '0'), 'receiver_name', 'Nguyen Van A', 'receiver_phone', '0912345678', 'receiver_address', '12 Pho Hue, Quan Hai Ba Trung, Ha Noi', 'zone', 'Same city', 'service', 'Standard', 'weight_kg', 1.5, 'cod_amount', 250000, 'shipping_fee', 25000, 'priced_by', 'standard tariff', 'ops_status', 'Created')) RETURNING *;
END;
