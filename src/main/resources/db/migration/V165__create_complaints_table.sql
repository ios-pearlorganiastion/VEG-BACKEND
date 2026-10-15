-- ============================================================
-- VegGo Fresh -- Complaints Module (V165)
-- Customer, Vendor, and Delivery complaints management.
-- All complaints go to Admin for review.
-- Admin can mark complaints as RESOLVED or UNRESOLVED.
-- Once marked RESOLVED, the original complainant can REOPEN the complaint.
-- All complaints are registered strictly by user token identity.
-- ============================================================

CREATE TABLE complaints (
    id UUID NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    ticket_number VARCHAR(40) NOT NULL,
    user_id UUID NOT NULL,
    user_role VARCHAR(30) NOT NULL,
    category VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    order_id UUID NULL,
    attachments TEXT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    admin_notes TEXT NULL,
    resolved_at TIMESTAMP(6) NULL,
    resolved_by UUID NULL,
    reopen_reason TEXT NULL,
    reopened_at TIMESTAMP(6) NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_complaints_ticket_number UNIQUE (ticket_number),
    CONSTRAINT fk_complaints_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_complaints_resolved_by FOREIGN KEY (resolved_by) REFERENCES users(id),
    CONSTRAINT fk_complaints_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE INDEX idx_complaints_user_id ON complaints(user_id);
CREATE INDEX idx_complaints_user_role ON complaints(user_role);
CREATE INDEX idx_complaints_status ON complaints(status);
CREATE INDEX idx_complaints_created_at ON complaints(created_at);
CREATE INDEX idx_complaints_order_id ON complaints(order_id);
CREATE INDEX idx_complaints_ticket_number ON complaints(ticket_number);
