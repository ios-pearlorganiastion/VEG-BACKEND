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
 * Delivery partner profile. Cross-module identity reference is by userId (UUID) only —
 * never a JPA relationship into com.veggofresh.auth.entity.User.
 */
@Entity
@Table(name = "delivery_partners")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class DeliveryPartnerProfile extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 50)
    private DeliveryKycStatus kycStatus = DeliveryKycStatus.PENDING;

    /** Set by DeliveryKycServiceImpl.rejectKyc() and cleared on approval or resubmission. Null unless kycStatus == REJECTED. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** The sections the admin sent back with the current rejection (comma-separated {@link DeliveryKycSection}); null otherwise. */
    @Column(name = "rejected_sections", length = 200)
    private String rejectedSections;

    /**
     * When the rider submitted the final onboarding step. NULL means the application is still a draft: the
     * only reliable "has this been sent for review?" signal, because {@link #kycStatus} is PENDING from the
     * moment the profile exists. Cleared again if the rider withdraws. See {@link DeliveryApplicationState}.
     */
    @Column(name = "application_submitted_at")
    private Instant applicationSubmittedAt;

    /** How many times the rider has submitted (never decreases; a withdrawal does not undo it). */
    @Column(name = "submission_count", nullable = false)
    private int submissionCount = 0;

    /** How many times an admin has rejected this rider's application. */
    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount = 0;

    @Column(name = "is_online", nullable = false)
    private boolean online = false;

    @Column(name = "current_latitude")
    private Double currentLatitude;

    @Column(name = "current_longitude")
    private Double currentLongitude;

    @Column(name = "vehicle_type", length = 50)
    private String vehicleType;

    // ── Account Settings fields (Phase A) ──────────────────────────────
    // fullName is a WORKAROUND: com.veggofresh.auth.entity.User has no name
    // field, and UserSummaryDto exposes only phone/email/role/verified/blocked.
    // Ideally this lives on Auth's User entity. Kept here, locally, until that
    // exists -- see NOTES.md.
    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(length = 255)
    private String email;

    /**
     * Personal photo, optional/single, uploaded via Cloudinary. Set through
     * PUT /api/delivery/account-settings alongside fullName/email -- did not exist
     * at all before this field was added.
     */
    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    /**
     * Cloudinary public_id for {@link #avatarUrl}, required to delete the old asset
     * when the avatar is replaced. Internal only -- never exposed on AccountSettingsResponseDto.
     */
    @Column(name = "avatar_public_id", length = 500)
    private String avatarPublicId;

    @Column(name = "vehicle_color", length = 50)
    private String vehicleColor;

    @Column(name = "push_notifications_enabled", nullable = false)
    private boolean pushNotificationsEnabled = true;

    @Column(name = "sms_alerts_enabled", nullable = false)
    private boolean smsAlertsEnabled = false;

    @Column(name = "email_newsletters_enabled", nullable = false)
    private boolean emailNewslettersEnabled = true;

    @Column(name = "emergency_contact_name", length = 255)
    private String emergencyContactName;

    @Column(name = "emergency_contact_relationship", length = 100)
    private String emergencyContactRelationship;

    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    // ── Onboarding fields (Phase C) ─────────────────────────────────────
    @Column(name = "city_of_operation", length = 100)
    private String cityOfOperation;

    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    @Column(name = "plate_number", length = 50)
    private String plateNumber;

    @Column(name = "vehicle_model", length = 100)
    private String vehicleModel;

    @Column(name = "manufacture_year")
    private Integer manufactureYear;

    // ── Bank details -- FLAGGED: belongs to Payment module long-term. ──
    // Stored here for now per team decision. accountNumber is stored PLAIN --
    // must be encrypted at rest before any production use. See NOTES.md.
    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "account_holder_name", length = 255)
    private String accountHolderName;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "ifsc_code", length = 20)
    private String ifscCode;

    @Column(name = "agreed_to_payout_terms", nullable = false)
    private boolean agreedToPayoutTerms = false;

    // ── Onboarding progress tracking ────────────────────────────────────
    @Column(name = "has_basic_info", nullable = false)
    private boolean hasBasicInfo = false;

    /** 0 = not started, 1 = license done, 2 = vehicle details done, 3 = bank details done (the final step, which submits). */
    @Column(name = "verification_step", nullable = false)
    private int verificationStep = 0;
}
