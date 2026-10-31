-- V167: align historical delivery earnings with what was actually paid.
--
-- Until now the Earnings screens used a hardcoded formula (base pay 20 + 8 per km) while the
-- wallet credited the order's admin-configured delivery fee, so the two disagreed. From this
-- release a delivery partner's earning IS the delivery fee that is credited to their wallet.
--
-- This corrects the OLD rows: for every earning that has a matching wallet credit
-- (reason ORDER_DELIVERY_SETTLEMENT for the same order and partner), set the earning to the
-- amount that was really paid. basePay carries the whole amount; distance_fare, peak_bonus and
-- tip are 0 (they were always 0 or formula-derived, and no such system exists).
--
-- Earnings with no matching wallet credit (e.g. nothing was ever paid) are left untouched.
-- Safe to re-run: only rows that still differ are changed.

UPDATE delivery_earnings de
SET base_pay      = paid.amount,
    distance_fare = 0,
    peak_bonus    = 0,
    tip           = 0,
    amount        = paid.amount
FROM (
    SELECT reference_id, user_id, SUM(amount) AS amount
    FROM wallet_transactions
    WHERE reason = 'ORDER_DELIVERY_SETTLEMENT'
      AND type   = 'CREDIT'
    GROUP BY reference_id, user_id
) paid
WHERE paid.reference_id = de.order_id
  AND paid.user_id      = de.delivery_partner_user_id
  AND de.amount        <> paid.amount;
