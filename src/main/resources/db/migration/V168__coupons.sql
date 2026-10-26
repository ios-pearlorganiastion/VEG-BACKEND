-- ============================================================
-- V168 -- Coupon management
--
-- Replaces the three hard-coded codes (SAVE10, SAVE20, FRESH50) with admin-managed coupons.
--
--   coupons                  one row per coupon (the rules)
--   coupon_redemptions       one row per CHECKOUT that used a coupon (a checkout of 1, 2 or all carts = ONE use)
--   coupon_redemption_orders how much of that discount each created order carries (its share)
--   customer_coupon_selections  the coupon a customer has "in hand" (typed in the cart, used at checkout)
--
-- Usage and budget are DERIVED from the redemption rows (never stored as counters), so they cannot drift:
--   uses        = redemptions with released_at IS NULL
--   budget used = sum of shares with cancelled_at IS NULL
-- A cancelled order sets its share's cancelled_at; when every share of a redemption is cancelled the redemption
-- is released (released_at), which gives the use back.
-- ============================================================

CREATE TABLE IF NOT EXISTS coupons (
    id UUID NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,

    code VARCHAR(40) NOT NULL,
    description VARCHAR(255) NULL,

    discount_type VARCHAR(20) NOT NULL,              -- PERCENT | FLAT
    discount_value NUMERIC(10,2) NOT NULL,           -- percent (1-100) or rupees
    max_discount NUMERIC(10,2) NULL,                 -- cap for the discount of one checkout
    min_order_amount NUMERIC(10,2) NOT NULL DEFAULT 0,   -- measured on the combined ITEMS total of the checkout

    starts_at TIMESTAMP(6) NULL,                     -- UTC; NULL = already started
    ends_at TIMESTAMP(6) NULL,                       -- UTC; NULL = never ends

    audience VARCHAR(30) NOT NULL DEFAULT 'EVERYONE',    -- EVERYONE | NEW_CUSTOMERS | RETURNING_CUSTOMERS
    min_previous_orders INT NOT NULL DEFAULT 0,          -- RETURNING_CUSTOMERS: at least N earlier non-cancelled orders

    total_usage_limit INT NULL,                      -- uses across all customers
    total_budget NUMERIC(12,2) NULL,                 -- maximum total rupees this coupon may give away
    per_customer_limit INT NULL,                     -- N uses per customer ...
    per_customer_period VARCHAR(20) NULL,            -- ... per DAY | WEEK | MONTH | LIFETIME (calendar periods, India time)

    listed BOOLEAN NOT NULL DEFAULT TRUE,            -- shown in the customers' offers list (FALSE = hidden code)
    enabled BOOLEAN NOT NULL DEFAULT TRUE,           -- the instant kill switch

    PRIMARY KEY (id),
    CONSTRAINT ck_coupons_type CHECK (discount_type IN ('PERCENT', 'FLAT')),
    CONSTRAINT ck_coupons_value CHECK (discount_value > 0 AND (discount_type = 'FLAT' OR discount_value <= 100)),
    CONSTRAINT ck_coupons_max_discount CHECK (max_discount IS NULL OR max_discount > 0),
    CONSTRAINT ck_coupons_min_order CHECK (min_order_amount >= 0),
    CONSTRAINT ck_coupons_dates CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_coupons_audience CHECK (audience IN ('EVERYONE', 'NEW_CUSTOMERS', 'RETURNING_CUSTOMERS')),
    CONSTRAINT ck_coupons_prev_orders CHECK (min_previous_orders >= 0),
    CONSTRAINT ck_coupons_usage_limit CHECK (total_usage_limit IS NULL OR total_usage_limit > 0),
    CONSTRAINT ck_coupons_budget CHECK (total_budget IS NULL OR total_budget > 0),
    CONSTRAINT ck_coupons_customer_limit CHECK (per_customer_limit IS NULL OR per_customer_limit > 0),
    CONSTRAINT ck_coupons_period CHECK (per_customer_period IS NULL OR per_customer_period IN ('DAY', 'WEEK', 'MONTH', 'LIFETIME')),
    CONSTRAINT ck_coupons_customer_limit_period CHECK ((per_customer_limit IS NULL) = (per_customer_period IS NULL))
);

-- A code is unique (case-insensitive) among coupons that are not deleted.
CREATE UNIQUE INDEX IF NOT EXISTS uk_coupons_code_active ON coupons (UPPER(code)) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_coupons_enabled ON coupons (enabled);

CREATE TABLE IF NOT EXISTS coupon_redemptions (
    id UUID NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,

    coupon_id UUID NOT NULL,
    user_id UUID NOT NULL,
    total_discount NUMERIC(12,2) NOT NULL,
    released_at TIMESTAMP(6) NULL,                   -- set when every order of the checkout was cancelled

    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_redemptions_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id),
    CONSTRAINT fk_coupon_redemptions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_coupon_redemptions_amount CHECK (total_discount > 0)
);

CREATE INDEX IF NOT EXISTS idx_coupon_redemptions_coupon ON coupon_redemptions (coupon_id, released_at);
CREATE INDEX IF NOT EXISTS idx_coupon_redemptions_user ON coupon_redemptions (coupon_id, user_id, created_at);

CREATE TABLE IF NOT EXISTS coupon_redemption_orders (
    id UUID NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,

    redemption_id UUID NOT NULL,
    order_id UUID NOT NULL,
    discount_amount NUMERIC(10,2) NOT NULL,
    cancelled_at TIMESTAMP(6) NULL,                  -- set when this order is cancelled/voided: the share goes with it

    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_redemption_orders_order UNIQUE (order_id),
    CONSTRAINT fk_coupon_redemption_orders_redemption FOREIGN KEY (redemption_id) REFERENCES coupon_redemptions (id),
    CONSTRAINT fk_coupon_redemption_orders_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_coupon_redemption_orders_amount CHECK (discount_amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_coupon_redemption_orders_redemption ON coupon_redemption_orders (redemption_id);

CREATE TABLE IF NOT EXISTS customer_coupon_selections (
    id UUID NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,

    user_id UUID NOT NULL,
    coupon_id UUID NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_customer_coupon_selections_user UNIQUE (user_id),
    CONSTRAINT fk_customer_coupon_selections_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_customer_coupon_selections_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id)
);

-- ------------------------------------------------------------
-- The three old hard-coded codes become normal coupons: active, same percentages / same flat 50, NO limits yet.
-- The admin can now set limits or switch them off. (They give a discount only on products whose vendor price is
-- below the customer price: a coupon is paid from the margin.)
-- ------------------------------------------------------------
INSERT INTO coupons (id, code, description, discount_type, discount_value, listed, enabled)
SELECT gen_random_uuid(), 'SAVE10', '10% off your items', 'PERCENT', 10, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM coupons WHERE UPPER(code) = 'SAVE10' AND deleted_at IS NULL);

INSERT INTO coupons (id, code, description, discount_type, discount_value, listed, enabled)
SELECT gen_random_uuid(), 'SAVE20', '20% off your items', 'PERCENT', 20, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM coupons WHERE UPPER(code) = 'SAVE20' AND deleted_at IS NULL);

INSERT INTO coupons (id, code, description, discount_type, discount_value, listed, enabled)
SELECT gen_random_uuid(), 'FRESH50', 'Flat Rs 50 off your items', 'FLAT', 50, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM coupons WHERE UPPER(code) = 'FRESH50' AND deleted_at IS NULL);
