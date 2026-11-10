-- ═══════════════════════════════════════════════════════════════════════
-- Delivery onboarding, phase 1.
--
-- 1. A real "submitted" marker. kyc_status is PENDING from the moment a profile exists,
--    so it cannot tell "just started" from "sent for review". application_submitted_at is
--    set only when the rider submits the final step. NULL = draft.
-- 2. Review history. One row per submit / withdraw / approve / reject, so an admin can see
--    an application was rejected once or several times, and why. The profile keeps only
--    counters (cheap to list) plus the CURRENT rejection reason and flagged sections.
-- 3. BACKGROUND_CHECK is no longer a document type. Remove its rows: an unknown value in
--    delivery_documents.document_type would make the row impossible to load.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE delivery_partners ADD COLUMN IF NOT EXISTS application_submitted_at TIMESTAMP(6);
ALTER TABLE delivery_partners ADD COLUMN IF NOT EXISTS submission_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE delivery_partners ADD COLUMN IF NOT EXISTS rejection_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE delivery_partners ADD COLUMN IF NOT EXISTS rejected_sections VARCHAR(200);

-- Riders who had already finished onboarding were in the review queue: keep them there
-- as "submitted". Everyone else becomes a draft (application_submitted_at stays NULL).
UPDATE delivery_partners
   SET application_submitted_at = updated_at,
       submission_count = 1
 WHERE verification_step >= 3;

-- Riders already rejected: record that it happened once (their reason stays on the profile).
UPDATE delivery_partners
   SET rejection_count = 1
 WHERE kyc_status = 'REJECTED';

CREATE TABLE IF NOT EXISTS delivery_kyc_review_history (
    id                        UUID         NOT NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at                TIMESTAMP(6) NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    delivery_partner_user_id  UUID         NOT NULL,
    review_action             VARCHAR(30)  NOT NULL,
    submission_number         INTEGER      NOT NULL,
    reason                    VARCHAR(500),
    sections                  VARCHAR(200),
    admin_user_id             UUID,
    PRIMARY KEY (id),
    CONSTRAINT fk_delivery_kyc_history_user FOREIGN KEY (delivery_partner_user_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_kyc_history_partner
    ON delivery_kyc_review_history (delivery_partner_user_id, created_at);

DELETE FROM delivery_documents WHERE document_type = 'BACKGROUND_CHECK';
