-- ============================================================
-- V164 -- One phone number may own one account PER ROLE
--
-- BEFORE: CONSTRAINT uk_users_phone UNIQUE (phone)
--         => a phone number could only ever have ONE account with ONE role. Logging in to the
--            Delivery or Vendor app with a number first used in the Customer app silently returned a
--            CUSTOMER token (the role sent by the app was ignored for existing numbers), so those
--            apps failed later with 403s.
--
-- AFTER:  one ACTIVE account per (phone, role). The same number can therefore be a customer, a vendor
--         and a delivery partner at the same time - each is its own account with its own wallet, bank
--         account, profile, notifications and block status - but never two accounts of the same role.
--
-- The rule only counts ACTIVE rows (deleted_at IS NULL), like V80 (vendor listings) and V163 (wishlists).
-- Admin "delete customer" soft-deletes a user; with the old rule that person's phone number could never
-- sign up again (duplicate-key 500). With the partial index they can start a fresh account.
--
-- Safe on existing data: phone is unique today, so every (phone, role) pair is already unique and the
-- new index can always be built. No rows are changed.
-- ============================================================

ALTER TABLE users DROP CONSTRAINT IF EXISTS uk_users_phone;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone_role
    ON users (phone, role)
    WHERE deleted_at IS NULL;
