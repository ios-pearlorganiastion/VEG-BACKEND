package com.veggofresh.delivery.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.UUID;

/**
 * A rider's request to change a verified detail after approval. The new values wait here, never touching the
 * profile, until an admin approves; the rider keeps working on the old verified details meanwhile.
 *
 * <p>{@link #getSection()} reuses {@link DeliveryKycSection}: BASIC_INFO (name, city), LICENSE, VEHICLE (type, plate,
 * model, year + insurance document) or BANK (bank details + bank proof). Values are small JSON maps of strings, so
 * one table serves every section.
 */
@Entity
@Table(name = "delivery_change_requests")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class DeliveryChangeRequest extends BaseEntity {

    @Column(name = "delivery_partner_user_id", nullable = false)
    private UUID deliveryPartnerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "section", nullable = false, length = 30)
    private DeliveryKycSection section;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryChangeRequestStatus status = DeliveryChangeRequestStatus.PENDING;

    /** What the profile held when the request was made (JSON map). */
    @Column(name = "previous_values", columnDefinition = "TEXT")
    private String previousValues;

    /** The new values the rider wants (JSON map). */
    @Column(name = "requested_values", nullable = false, columnDefinition = "TEXT")
    private String requestedValues;

    /** The new document (license photo, insurance, bank proof); null for BASIC_INFO. */
    @Column(name = "document_url", length = 500)
    private String documentUrl;

    @Column(name = "document_public_id", length = 500)
    private String documentPublicId;

    /** Why it was rejected; null otherwise. */
    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** The admin who approved or rejected; null while pending and when the rider cancelled. */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
