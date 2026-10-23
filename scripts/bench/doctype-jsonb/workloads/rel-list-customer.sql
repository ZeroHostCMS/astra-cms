-- 20 newest orders of one customer on the traditional table.
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM orders_rel WHERE customer = 'CUST-' || lpad(:c::text, 7, '0') ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
