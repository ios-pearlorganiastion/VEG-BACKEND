package com.veggofresh.delivery.entity;

/**
 * Where a rider's application actually is, in plain words.
 *
 * <p>{@code kycStatus} alone cannot say this: it is PENDING from the moment the profile exists, so a rider who
 * has only typed their name looks identical to one whose complete application is waiting for the admin. The
 * missing piece is {@code applicationSubmittedAt}, which is set only when the rider submits the final step.
 *
 * <pre>
 * DRAFT         never submitted (or withdrawn). Rider can edit. NOT visible in the admin review queue.
 * UNDER_REVIEW  submitted, waiting for the admin. Details are LOCKED; the rider may withdraw.
 * APPROVED      verified. Details are LOCKED (changes need a review).
 * REJECTED      the admin sent it back with a reason. Rider can edit and resubmit.
 * </pre>
 */
public enum DeliveryApplicationState {
    DRAFT,
    UNDER_REVIEW,
    APPROVED,
    REJECTED;

    public static DeliveryApplicationState of(DeliveryPartnerProfile profile) {
        DeliveryKycStatus status = profile.getKycStatus();
        if (status == DeliveryKycStatus.APPROVED) {
            return APPROVED;
        }
        if (status == DeliveryKycStatus.REJECTED) {
            return REJECTED;
        }
        return profile.getApplicationSubmittedAt() != null ? UNDER_REVIEW : DRAFT;
    }

    /** Only a draft or a rejected application may be edited. */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }
}
