-- ═══════════════════════════════════════════════════════════════════════
-- Delivery partners: reviewed change requests after approval.
--
-- An APPROVED rider keeps working on their verified details. A change to a
-- verified detail (name/city, license, vehicle, bank) is stored here as a PENDING
-- request and is only applied to the profile when an admin approves it.
--
-- section          BASIC_INFO | LICENSE | VEHICLE | BANK
-- status           PENDING | APPROVED | REJECTED | CANCELLED
-- previous_values  JSON snapshot of what the profile held when the request was made
-- requested_values JSON of the new values
-- document_*       the new document (license photo / insurance / bank proof), held
--                  here until approval; copied to delivery_documents on approval
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS delivery_change_requests (
    id                        UUID          NOT NULL,
    created_at                TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at                TIMESTAMP(6)  NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    delivery_partner_user_id  UUID          NOT NULL,
    section                   VARCHAR(30)   NOT NULL,
    status                    VARCHAR(20)   NOT NULL,
    previous_values           TEXT,
    requested_values          TEXT          NOT NULL,
    document_url              VARCHAR(500),
    document_public_id        VARCHAR(500),
    decision_reason           VARCHAR(500),
    decided_by                UUID,
    decided_at                TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_delivery_change_requests_user FOREIGN KEY (delivery_partner_user_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_change_requests_partner
    ON delivery_change_requests (delivery_partner_user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_delivery_change_requests_status
    ON delivery_change_requests (status, created_at);

-- At most ONE open request per rider per section, enforced by the database as well as the service.
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_change_requests_open
    ON delivery_change_requests (delivery_partner_user_id, section)
    WHERE status = 'PENDING' AND deleted_at IS NULL;
