-- ============================================================
-- V167 -- Vendor price
--
-- Every catalog product gets a VENDOR PRICE: the amount the vendor is paid per unit. The customer price
-- (catalog_products.price) is unchanged. The difference (price - vendor_price) is the platform's MARGIN;
-- coupons are paid out of that margin, so a coupon can never make the platform lose money.
--
-- Existing products start with vendor_price = price (margin 0): vendor payouts stay EXACTLY as they are today
-- until an admin lowers the vendor price of a product.
--
-- order_items.vendor_unit_price snapshots the vendor price at the moment an order is placed (like price
-- already is), so later price changes never alter an old order. NULL on old orders = "use the customer price"
-- (the old behaviour), so nothing in flight changes.
-- ============================================================

ALTER TABLE catalog_products ADD COLUMN IF NOT EXISTS vendor_price NUMERIC(10,2);

UPDATE catalog_products SET vendor_price = price WHERE vendor_price IS NULL;

ALTER TABLE catalog_products ALTER COLUMN vendor_price SET NOT NULL;

ALTER TABLE catalog_products DROP CONSTRAINT IF EXISTS ck_catalog_products_vendor_price;
ALTER TABLE catalog_products
    ADD CONSTRAINT ck_catalog_products_vendor_price CHECK (vendor_price > 0 AND vendor_price <= price);

ALTER TABLE order_items ADD COLUMN IF NOT EXISTS vendor_unit_price NUMERIC(10,2);
