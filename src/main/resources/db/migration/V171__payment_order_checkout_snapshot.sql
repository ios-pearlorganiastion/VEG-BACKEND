-- ═══════════════════════════════════════════════════════════════════════
-- Payment-first checkout.
--
-- Orders are no longer created at checkout. Checkout only prepares the
-- payment (one Razorpay order) and writes down what is being bought; the
-- orders are created when the payment is AUTHORIZED (customer's verify call
-- or the Razorpay webhook, whichever arrives first).
--
-- checkout_snapshot       opaque JSON owned by the customer module: carts,
--                         address, slot, coupon and the amount of each cart
--                         as the customer saw it. The payment module never
--                         reads it, it only hands it back at authorization.
-- orders_placed_at        set once, in the same transaction that creates the
--                         orders. This is what makes "place the orders" safe
--                         to reach from both verify and the webhook.
-- placement_failure_reason set when the payment was authorized but the orders
--                         could not be placed (cart changed, coupon dead...).
--                         The payment is then never captured.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS checkout_snapshot TEXT;
ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS orders_placed_at TIMESTAMP;
ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS placement_failure_reason VARCHAR(500);
