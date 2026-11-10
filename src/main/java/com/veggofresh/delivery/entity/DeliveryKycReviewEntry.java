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

import java.util.UUID;

/**
 * One event in a rider's application history: submitted, withdrawn, approved or rejected (with the reason and
 * the sections that were sent back). Written, never edited, so the admin can always see that an application was
 * rejected once or several times, and why. The single {@code rejection_reason} column on the profile only ever
 * holds the latest reason.
 */
@Entity
@Table(name = "delivery_kyc_review_history")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class DeliveryKycReviewEntry extends BaseEntity {

    @Column(name = "delivery_partner_user_id", nullable = false)
    private UUID deliveryPartnerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_action", nullable = false, length = 30)
    private DeliveryKycReviewAction action;

    /** Which submission this belongs to (1 = first time the rider submitted). */
    @Column(name = "submission_number", nullable = false)
    private int submissionNumber;

    @Column(name = "reason", length = 500)
    private String reason;

    /** Comma-separated {@link DeliveryKycSection} names; null when nothing specific was flagged. */
    @Column(name = "sections", length = 200)
    private String sections;

    /** The admin who approved or rejected; null for the rider's own actions. */
    @Column(name = "admin_user_id")
    private UUID adminUserId;

    public static DeliveryKycReviewEntry of(UUID partnerUserId, DeliveryKycReviewAction action, int submissionNumber,
            String reason, String sections, UUID adminUserId) {
        DeliveryKycReviewEntry entry = new DeliveryKycReviewEntry();
        entry.setDeliveryPartnerUserId(partnerUserId);
        entry.setAction(action);
        entry.setSubmissionNumber(submissionNumber);
        entry.setReason(reason);
        entry.setSections(sections);
        entry.setAdminUserId(adminUserId);
        return entry;
    }
}
