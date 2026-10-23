-- Traditional-table insert named from a sequence (no counter row lock).
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT 1 FROM astra_doc WHERE doctype = 'Customer' AND name = 'CUST-' || lpad(:c::text, 7, '0');
SELECT nextval('bench_order_seq') AS current \gset
INSERT INTO orders_rel (tenant_id, name, owner, customer, receiver_name, receiver_phone, receiver_address, zone, service, weight_kg, cod_amount, shipping_fee, priced_by, ops_status) VALUES ('hanoi-express', 'BNQ-20261023-' || lpad(:current::text, 8, '0'), 'user' || :client_id, 'CUST-' || lpad(:c::text, 7, '0'), 'Nguyen Van A', '0912345678', '12 Pho Hue, Quan Hai Ba Trung, Ha Noi', 'Same city', 'Standard', 1.5, 250000, 25000, 'standard tariff', 'Created') RETURNING *;
END;
