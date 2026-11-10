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

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per document type per partner (LICENSE, INSURANCE, BANK_INFO).
 * Replaces DeliveryPartnerProfile.kycStatus for DISPLAY purposes (the vault screen needs
 * per-document status). kycStatus stays on the profile as the single boolean gate for
 * going online -- see DeliveryProfileServiceImpl for how the two are kept in sync.
 */
@Entity
@Table(name = "delivery_documents")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class DeliveryDocument extends BaseEntity {

    @Column(name = "delivery_partner_user_id", nullable = false)
    private UUID deliveryPartnerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 50)
    private DeliveryDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DeliveryDocumentStatus status = DeliveryDocumentStatus.PENDING;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /**
     * Cloudinary public_id for {@link #fileUrl}, required to delete the old asset when
     * this document type is re-uploaded (KYC vault re-upload, or onboarding Step 1/2
     * license/insurance photos, which write into this same table). Internal only --
     * never exposed on DeliveryDocumentResponseDto.
     */
    @Column(name = "public_id", length = 500)
    private String publicId;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;
}
