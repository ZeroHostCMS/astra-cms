-- 30 days of history for tenant saigon-express, for scripts/demo-tiers.sh (the AI sessions and backtests read it):
--   * Shop ABC (customer ABC): 1,350 submitted orders, 42 of their parcels returned (3.1 %), a fifth of them bulky;
--   * other customers: 900 orders, delivered by couriers bt01..bt08; courier bt05 fails about a third of its attempts;
--   * hub scans with the weight measured at the hub: 15 parcels at HCM-Tân Bình weigh 35-80 % more than declared;
--   * 40 COD payouts to shops, 6 of them paid well under what was owed.
-- The planted anomalies carry seed_label, so a backtest can count hits and misses. This is demo data, written straight
-- into astra_doc with past timestamps (the API always stamps now()); rerunning replaces it. Hooks did not run on it:
-- no ledger postings, no price breakdown.
BEGIN;
SELECT setseed(0.42) \gset
DELETE FROM astra_doc WHERE tenant_id = 'saigon-express' AND owner = 'seed-history';

CREATE TEMP TABLE h_orders ON COMMIT DROP AS
SELECT i,
       CASE WHEN i <= 1350 THEN 'ABC' ELSE (ARRAY['C001','C002','C003'])[1 + (i % 3)] END AS customer,
       now() - (random() * interval '29 days') - interval '1 hour' AS at,
       CASE WHEN r1 < 0.35 THEN 'Same city' WHEN r1 < 0.85 THEN 'Domestic' ELSE 'Remote' END AS zone,
       CASE WHEN random() < 0.12 THEN 'Express' ELSE 'Standard' END AS service,
       round((0.2 + random() ^ 2 * 6)::numeric, 2) AS kg,
       CASE WHEN random() < 0.6 THEN (10 + floor(random() * 190)::int) * 10000 ELSE 0 END AS cod,
       random() < 0.2 AS bulky,
       'bt0' || (1 + floor(random() * 8)::int) AS courier
FROM (SELECT g AS i, random() AS r1 FROM generate_series(1, 2250) g) s;

-- tariff of rules/standard-tariff.drl, rounded up to the thousand
CREATE TEMP TABLE h_priced ON COMMIT DROP AS
SELECT o.*, ceil((base * CASE WHEN service = 'Express' THEN 1.5 ELSE 1 END
                  + CASE WHEN cod > 0 THEN greatest(5000, ceil(cod * 0.01)) ELSE 0 END) / 1000) * 1000 AS fee
FROM (SELECT h.*, CASE zone WHEN 'Same city' THEN 18000 + 2500 * ceil(greatest(0, kg - 1) / 0.5)
                            WHEN 'Remote' THEN 45000 + 7000 * ceil(greatest(0, kg - 1) / 0.5)
                            ELSE 30000 + 5000 * ceil(greatest(0, kg - 1) / 0.5) END AS base
      FROM h_orders h) o;

-- delivery outcome: ABC returns 42 parcels; bt05 fails a third of the others' attempts; everyone else about 4 %
CREATE TEMP TABLE h_parcels ON COMMIT DROP AS
SELECT p.*,
       CASE WHEN p.customer = 'ABC' AND p.i % 32 = 7 AND p.i <= 1344 THEN 'Returned'
            WHEN p.customer <> 'ABC' AND p.courier = 'bt05' AND random() < 0.34 THEN 'Failed Delivery'
            WHEN p.customer <> 'ABC' AND random() < 0.04 THEN 'Failed Delivery'
            ELSE 'Delivered' END AS status,
       (ARRAY['HCM-Tân Bình','HCM-Thủ Đức','DN-Hải Châu','HN-Long Biên'])[1 + (p.i % 4)] AS hub
FROM h_priced p;
UPDATE h_parcels SET courier = 'bt01' WHERE customer = 'ABC' AND courier = 'bt05';   -- keep bt05's label clean

INSERT INTO astra_doc (tenant_id, doctype, name, docstatus, owner, created_at, modified_at, data)
SELECT 'saigon-express', 'CustomerOrder', 'ORD-H-' || lpad(i::text, 7, '0'), 1, 'seed-history', at, at,
       jsonb_build_object('customer', customer, 'receiver_name', 'Khách lịch sử ' || i, 'receiver_phone', '09' || lpad((10000000 + i)::text, 8, '0'),
           'receiver_address', 'Địa chỉ ' || i, 'zone', zone, 'service', service, 'weight_kg', kg, 'cod_amount', cod,
           'length_cm', CASE WHEN bulky THEN 50 ELSE 20 END, 'width_cm', CASE WHEN bulky THEN 40 ELSE 15 END,
           'height_cm', CASE WHEN bulky THEN 35 ELSE 10 END, 'shipping_fee', fee, 'priced_by', 'Standard tariff (seed)',
           'waybill', 'WB-H-' || lpad(i::text, 8, '0'), 'einvoice_status', 'Issued')
FROM h_parcels;

INSERT INTO astra_doc (tenant_id, doctype, name, docstatus, owner, created_at, modified_at, data)
SELECT 'saigon-express', 'Waybill', 'WB-H-' || lpad(i::text, 8, '0'), 0, 'seed-history', at, at + interval '20 hours',
       jsonb_build_object('order', 'ORD-H-' || lpad(i::text, 7, '0'), 'receiver_name', 'Khách lịch sử ' || i,
           'ops_status', status, 'current_hub', hub, 'courier', courier, 'carrier_status', 'Booked',
           'declared_weight_kg', kg,
           'measured_weight_kg', round(kg * (0.97 + random() * 0.07)::numeric, 2),
           'scan_log', jsonb_build_array(jsonb_build_object('status', 'Created', 'at', at),
                                         jsonb_build_object('status', 'At Hub', 'hub', hub, 'at', at + interval '6 hours'),
                                         jsonb_build_object('status', status, 'hub', hub, 'at', at + interval '20 hours')))
       || CASE WHEN courier = 'bt05' AND status = 'Failed Delivery' THEN '{"seed_label":"courier-bt05"}'::jsonb ELSE '{}'::jsonb END
FROM h_parcels;

-- the planted weight anomaly: 15 parcels at one hub weighed 35-80 % above the declared weight
UPDATE astra_doc SET data = data || jsonb_build_object('current_hub', 'HCM-Tân Bình',
           'measured_weight_kg', round(((data->>'declared_weight_kg')::numeric * (1.35 + random() * 0.45)::numeric), 2),
           'seed_label', 'weight-under-declared')
WHERE tenant_id = 'saigon-express' AND doctype = 'Waybill' AND owner = 'seed-history'
  AND name IN (SELECT 'WB-H-' || lpad(i::text, 8, '0') FROM h_parcels WHERE i % 100 = 11 AND kg >= 0.5 ORDER BY i LIMIT 15);

-- 40 COD payouts; 6 paid 55-85 % of what was owed
INSERT INTO astra_doc (tenant_id, doctype, name, docstatus, owner, created_at, modified_at, data)
SELECT 'saigon-express', 'CODRemittance', 'CODR-H-' || lpad(g::text, 4, '0'), 1, 'seed-history', at, at,
       jsonb_build_object('customer', (ARRAY['ABC','C001','C002','C003'])[1 + (g % 4)], 'outstanding', owed,
           'amount', CASE WHEN g % 7 = 3 THEN round(owed * (0.55 + random() * 0.3)::numeric, -3) ELSE owed END,
           'collected_until', to_char(at - (1 + floor(random() * 4)::int) * interval '1 day', 'YYYY-MM-DD'),
           'bank_reference', 'VCB-H' || g)
       || CASE WHEN g % 7 = 3 THEN '{"seed_label":"cod-underpaid"}'::jsonb ELSE '{}'::jsonb END
FROM (SELECT g, now() - (random() * interval '29 days') AS at, (5 + floor(random() * 60)::int) * 100000 AS owed
      FROM generate_series(1, 40) g) s;

-- Shop ABC's 30-day profile, as CustomerProfileJob computes it
UPDATE astra_doc SET data = data || jsonb_build_object('monthly_orders',
           (SELECT count(*) FROM h_parcels WHERE customer = 'ABC'),
           'return_rate', round((SELECT count(*) FILTER (WHERE status = 'Returned')::numeric / count(*) FROM h_parcels WHERE customer = 'ABC'), 4))
WHERE tenant_id = 'saigon-express' AND doctype = 'Customer' AND name = 'ABC';

SELECT doctype, count(*) AS rows, count(*) FILTER (WHERE data ? 'seed_label') AS labelled
FROM astra_doc WHERE tenant_id = 'saigon-express' AND owner = 'seed-history' GROUP BY doctype ORDER BY doctype;
COMMIT;
