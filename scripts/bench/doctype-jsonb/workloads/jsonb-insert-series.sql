-- DocumentStore.insert of a CustomerOrder as the kernel does it: Link check on customer, gap-free naming
-- series (one counter row per tenant and day, row lock held until commit), INSERT ... RETURNING *.
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT 1 FROM astra_doc WHERE doctype = 'Customer' AND name = 'CUST-' || lpad(:c::text, 7, '0');
INSERT INTO astra_series (tenant_id, series_key, current) VALUES ('hanoi-express', 'BNC-.20261023.-.########', 1) ON CONFLICT (tenant_id, series_key) DO UPDATE SET current = astra_series.current + 1 RETURNING current \gset
INSERT INTO astra_doc (id, tenant_id, doctype, name, owner, data) VALUES (gen_random_uuid(), 'hanoi-express', 'CustomerOrder', 'BNC-20261023-' || lpad(:current::text, 8, '0'), 'user' || :client_id, jsonb_build_object('customer', 'CUST-' || lpad(:c::text, 7, '0'), 'receiver_name', 'Nguyen Van A', 'receiver_phone', '0912345678', 'receiver_address', '12 Pho Hue, Quan Hai Ba Trung, Ha Noi', 'zone', 'Same city', 'service', 'Standard', 'weight_kg', 1.5, 'cod_amount', 250000, 'shipping_fee', 25000, 'priced_by', 'standard tariff', 'ops_status', 'Created')) RETURNING *;
END;
