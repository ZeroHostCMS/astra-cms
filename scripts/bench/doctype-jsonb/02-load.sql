-- DocType JSONB benchmark: data. Generates :rows CustomerOrder rows once into a typed staging table,
-- then copies the same rows into all three storage shapes, so every measurement compares identical data.
-- Shape mirrors astra-apps/express CustomerOrder plus an ops_status field (the Waybill status, used here
-- as the low-cardinality, frequently updated field every order system has).
--   2,000,000 orders/day: :rows = 5,000,000 covers 2.5 days (2026-10-20 00:00 .. 2026-10-22 12:00).
--   Tenants: hanoi-express 80 %, saigon-express 18 %, danang-express 2 %.
--   1,000,000 distinct customers per tenant namespace.

\set ON_ERROR_STOP on
\timing on
SET ROLE astra_app;
SELECT set_config('astra.tenant', 'hanoi-express', false);   -- RLS WITH CHECK needs a tenant bound; overridden per row below

CREATE UNLOGGED TABLE gen AS
SELECT
    gen_random_uuid()                                            AS id,
    CASE WHEN r1 < 0.80 THEN 'hanoi-express' WHEN r1 < 0.98 THEN 'saigon-express' ELSE 'danang-express' END AS tenant_id,
    'ORD-' || to_char(ts, 'YYYYMMDD') || '-' || lpad(n::text, 8, '0')  AS name,
    CASE WHEN r2 < 0.05 THEN 0 WHEN r2 < 0.97 THEN 1 ELSE 2 END::smallint AS docstatus,
    'user' || (n % 5000)                                         AS owner,
    ts                                                           AS created_at,
    ts + (r3 * interval '36 hours')                              AS modified_at,
    1 + (r4 * 6)::int                                            AS version,
    'CUST-' || lpad(((n::bigint * 7919) % 1000000)::text, 7, '0')        AS customer,
    'Nguyen Van ' || chr(65 + (n % 26))                          AS receiver_name,
    '09' || lpad(((n::bigint * 31) % 100000000)::text, 8, '0')           AS receiver_phone,
    (n % 999) || ' Pho ' || md5(n::text) || ', Quan ' || (n % 12) || ', Ha Noi' AS receiver_address,
    CASE WHEN r5 < 0.60 THEN 'Same city' WHEN r5 < 0.90 THEN 'Domestic' ELSE 'Remote' END AS zone,
    CASE WHEN r6 < 0.70 THEN 'Standard' ELSE 'Express' END       AS service,
    round((0.1 + r7 * 20)::numeric, 2)::real                     AS weight_kg,
    CASE WHEN r8 < 0.35 THEN 0 ELSE round((r8 * 5000000)::numeric, -3) END::numeric(18,2) AS cod_amount,
    round((15000 + r7 * 90000)::numeric, -2)::numeric(18,2)      AS shipping_fee,
    CASE WHEN r6 < 0.9 THEN 'standard tariff' ELSE 'tenant wasm rule' END AS priced_by,
    CASE WHEN r2 < 0.05 THEN NULL ELSE 'WB-2610-' || lpad(n::text, 8, '0') END AS waybill,
    CASE WHEN r9 < 0.55 THEN 'Delivered' WHEN r9 < 0.70 THEN 'Out for Delivery' WHEN r9 < 0.82 THEN 'At Hub'
         WHEN r9 < 0.90 THEN 'Picked Up' WHEN r9 < 0.95 THEN 'Created' WHEN r9 < 0.98 THEN 'Failed Delivery'
         WHEN r9 < 0.995 THEN 'Returned' ELSE 'Cancelled' END  AS ops_status,
    CASE WHEN r3 < 0.3 THEN 'Giao gio hanh chinh, goi truoc khi giao' ELSE NULL END AS note
FROM (
    SELECT n,
           timestamptz '2026-10-20 00:00:00+07' + (n - 1) * (interval '60 hours' / :rows) AS ts,
           random() r1, random() r2, random() r3, random() r4, random() r5,
           random() r6, random() r7, random() r8, random() r9
    FROM generate_series(1, :rows) n
) s;

ANALYZE gen;

-- ---------------------------------------------------------------- orders_rel
INSERT INTO orders_rel
SELECT id, tenant_id, name, docstatus, owner, created_at, modified_at, version, customer, receiver_name,
       receiver_phone, receiver_address, zone, service, weight_kg, cod_amount, shipping_fee, priced_by,
       waybill, ops_status, note
FROM gen WHERE tenant_id = 'hanoi-express';
SELECT set_config('astra.tenant', 'saigon-express', false);
INSERT INTO orders_rel SELECT id, tenant_id, name, docstatus, owner, created_at, modified_at, version, customer, receiver_name,
       receiver_phone, receiver_address, zone, service, weight_kg, cod_amount, shipping_fee, priced_by, waybill, ops_status, note
FROM gen WHERE tenant_id = 'saigon-express';
SELECT set_config('astra.tenant', 'danang-express', false);
INSERT INTO orders_rel SELECT id, tenant_id, name, docstatus, owner, created_at, modified_at, version, customer, receiver_name,
       receiver_phone, receiver_address, zone, service, weight_kg, cod_amount, shipping_fee, priced_by, waybill, ops_status, note
FROM gen WHERE tenant_id = 'danang-express';

-- ---------------------------------------------------------------- astra_doc and astra_doc_part (identical JSON documents)
CREATE OR REPLACE FUNCTION gen_doc(g gen) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
  SELECT jsonb_strip_nulls(jsonb_build_object(
      'customer', g.customer, 'receiver_name', g.receiver_name, 'receiver_phone', g.receiver_phone,
      'receiver_address', g.receiver_address, 'zone', g.zone, 'service', g.service,
      'weight_kg', g.weight_kg, 'cod_amount', g.cod_amount, 'shipping_fee', g.shipping_fee,
      'priced_by', g.priced_by, 'waybill', g.waybill, 'ops_status', g.ops_status, 'note', g.note))
$$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['hanoi-express', 'saigon-express', 'danang-express'] LOOP
    PERFORM set_config('astra.tenant', t, false);
    INSERT INTO astra_doc (id, tenant_id, doctype, name, docstatus, owner, created_at, modified_at, version, data)
    SELECT id, tenant_id, 'CustomerOrder', name, docstatus, owner, created_at, modified_at, version, gen_doc(gen)
    FROM gen WHERE tenant_id = t;
    INSERT INTO astra_doc_part (id, tenant_id, doctype, name, docstatus, owner, created_at, modified_at, version, data)
    SELECT id, tenant_id, 'CustomerOrder', name, docstatus, owner, created_at, modified_at, version, gen_doc(gen)
    FROM gen WHERE tenant_id = t;
  END LOOP;
END $$;

-- Customer master documents share the table with the orders, as they do in the kernel.
SELECT set_config('astra.tenant', 'hanoi-express', false);
INSERT INTO astra_doc (tenant_id, doctype, name, docstatus, owner, created_at, modified_at, data)
SELECT 'hanoi-express', 'Customer', 'CUST-' || lpad(n::text, 7, '0'), 0, 'user1',
       timestamptz '2026-01-01+07' + n * interval '20 seconds', timestamptz '2026-01-01+07' + n * interval '20 seconds',
       jsonb_build_object('full_name', 'Khach hang ' || n, 'phone', '09' || lpad(n::text, 8, '0'),
                          'segment', CASE WHEN n % 10 = 0 THEN 'B2B' ELSE 'Retail' END, 'email', 'c' || n || '@example.com')
FROM generate_series(0, 999999) n;

RESET ROLE;
