-- Ops list on the traditional table.
\set s random(1, 8)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM orders_rel WHERE ops_status = (ARRAY['Created','Picked Up','At Hub','Out for Delivery','Delivered','Failed Delivery','Returned','Cancelled'])[:s] ORDER BY modified_at DESC, name DESC LIMIT 21;
END;
