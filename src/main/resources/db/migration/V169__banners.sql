-- ============================================================
-- V169 -- Banners (admin-managed hero banners shown in the customer app)
--
-- Replaces the two hard-coded fake banners returned by GET /api/customer/banners.
-- A banner = an image + text, optionally advertising a coupon code and/or opening a category or product when tapped,
-- optionally scheduled, ordered by the admin, with an on/off switch.
-- ============================================================

CREATE TABLE IF NOT EXISTS banners (
    id UUID NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,

    title VARCHAR(120) NOT NULL,
    subtitle VARCHAR(255) NULL,

    image_url VARCHAR(500) NOT NULL,
    image_public_id VARCHAR(255) NOT NULL,           -- Cloudinary public_id, so the image can be removed later

    coupon_code VARCHAR(40) NULL,                    -- UPPER-CASE; checked against the coupons when the banner is saved

    action_type VARCHAR(20) NOT NULL DEFAULT 'NONE', -- what a tap opens: NONE | CATEGORY | PRODUCT
    action_target_id UUID NULL,                      -- the category / product id

    starts_at TIMESTAMP(6) NULL,                     -- UTC; NULL = show from now
    ends_at TIMESTAMP(6) NULL,                       -- UTC, exclusive; NULL = never ends
    display_order INT NOT NULL DEFAULT 0,            -- lower first
    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    CONSTRAINT ck_banners_action_type CHECK (action_type IN ('NONE', 'CATEGORY', 'PRODUCT')),
    CONSTRAINT ck_banners_action_target CHECK ((action_type = 'NONE') = (action_target_id IS NULL)),
    CONSTRAINT ck_banners_dates CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_banners_display_order CHECK (display_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_banners_showing ON banners (enabled, display_order);
