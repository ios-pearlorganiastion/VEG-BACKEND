package com.veggofresh.delivery.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.dto.DeliveryKycBankDto;
import com.veggofresh.delivery.dto.DeliveryKycDetailDto;
import com.veggofresh.delivery.dto.DeliveryKycDocumentDto;
import com.veggofresh.delivery.dto.DeliveryKycEmergencyContactDto;
import com.veggofresh.delivery.dto.DeliveryKycReviewDto;
import com.veggofresh.delivery.entity.DeliveryDocument;
import com.veggofresh.delivery.entity.DeliveryDocumentType;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryDocumentRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.delivery.service.DeliveryKycService;
import com.veggofresh.platform.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real implementation -- resolves the blocking dependency Admin's
 * AdminDeliveryKycController was built against last round. See that controller's
 * javadoc and Admin's NOTES_ADMIN.md for the original contract this fulfills.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryKycServiceImpl implements DeliveryKycService {

    private static final int SUBMITTED_STEP = 3;

    /** Status shown for a document type the partner has not uploaded yet. */
    private static final String NOT_UPLOADED = "NOT_UPLOADED";

    private final DeliveryPartnerProfileRepository partnerRepository;
    private final DeliveryDocumentRepository documentRepository;
    private final UserLookupService userLookupService;

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryKycReviewDto> listPendingKyc(Pageable pageable) {
        return partnerRepository.findByKycStatusAndVerificationStep(DeliveryKycStatus.PENDING, SUBMITTED_STEP, pageable)
                .map(this::mapToDto);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryKycDetailDto getKycDetail(UUID userId) {
        DeliveryPartnerProfile p = getPartner(userId);
        String phone = userLookupService.findById(p.getUserId()).map(UserSummaryDto::getPhone).orElse(null);

        List<DeliveryKycDocumentDto> documents = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        buildDocuments(p.getUserId(), documents, missing);

        return DeliveryKycDetailDto.builder()
                // ---- same fields as the original review response ----
                .userId(p.getUserId())
                .fullName(p.getFullName())
                .phone(phone)
                .vehicleType(p.getVehicleType())
                .vehicleModel(p.getVehicleModel())
                .plateNumber(p.getPlateNumber())
                .licenseNumber(p.getLicenseNumber())
                .kycStatus(p.getKycStatus() == null ? null : p.getKycStatus().name())
                .rejectionReason(p.getRejectionReason())
                .submittedAt(p.getUpdatedAt())
                // ---- the rest of the onboarding data ----
                .email(p.getEmail())
                .avatarUrl(p.getAvatarUrl())
                .cityOfOperation(p.getCityOfOperation())
                .vehicleColor(p.getVehicleColor())
                .manufactureYear(p.getManufactureYear())
                .emergencyContact(DeliveryKycEmergencyContactDto.builder()
                        .name(p.getEmergencyContactName())
                        .relationship(p.getEmergencyContactRelationship())
                        .phone(p.getEmergencyContactPhone())
                        .build())
                .bank(DeliveryKycBankDto.builder()
                        .bankName(p.getBankName())
                        .accountHolderName(p.getAccountHolderName())
                        .accountNumber(p.getAccountNumber())
                        .ifscCode(p.getIfscCode())
                        .agreedToPayoutTerms(p.isAgreedToPayoutTerms())
                        .provided(hasText(p.getAccountNumber()) && hasText(p.getIfscCode()))
                        .build())
                .onboardingStep(p.getVerificationStep())
                .basicInfoCompleted(p.isHasBasicInfo())
                .registeredAt(p.getCreatedAt())
                .lastUpdatedAt(p.getUpdatedAt())
                .documents(documents)
                .allDocumentsUploaded(missing.isEmpty())
                .missingDocuments(missing)
                .build();
    }

    @Override
    public void approveKyc(UUID userId) {
        DeliveryPartnerProfile partner = getPartner(userId);
        partner.setKycStatus(DeliveryKycStatus.APPROVED);
        partner.setRejectionReason(null);
        partnerRepository.save(partner);
    }

    @Override
    public void rejectKyc(UUID userId, String reason) {
        DeliveryPartnerProfile partner = getPartner(userId);
        partner.setKycStatus(DeliveryKycStatus.REJECTED);
        partner.setRejectionReason(reason);
        partnerRepository.save(partner);
    }

    private DeliveryPartnerProfile getPartner(UUID userId) {
        return partnerRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));
    }

    /**
     * One entry per document type, always in the same order. A type that was never uploaded (or has no
     * file) is listed as NOT_UPLOADED so the admin can see at a glance what is missing.
     */
    private void buildDocuments(UUID userId, List<DeliveryKycDocumentDto> out, List<String> missing) {
        Map<DeliveryDocumentType, DeliveryDocument> latest = new EnumMap<>(DeliveryDocumentType.class);
        for (DeliveryDocument d : documentRepository.findByDeliveryPartnerUserId(userId)) {
            if (d.getDocumentType() != null) {
                latest.merge(d.getDocumentType(), d, DeliveryKycServiceImpl::newer);
            }
        }
        for (DeliveryDocumentType type : DeliveryDocumentType.values()) {
            DeliveryDocument d = latest.get(type);
            if (d == null || !hasText(d.getFileUrl())) {
                out.add(DeliveryKycDocumentDto.builder()
                        .documentType(type.name())
                        .status(NOT_UPLOADED)
                        .uploaded(false)
                        .build());
                missing.add(type.name());
            } else {
                out.add(DeliveryKycDocumentDto.builder()
                        .documentType(type.name())
                        .status(d.getStatus() == null ? "PENDING" : d.getStatus().name())
                        .fileUrl(d.getFileUrl())
                        .uploaded(true)
                        .expiryDate(d.getExpiryDate())
                        .uploadedAt(d.getCreatedAt())
                        .lastUpdatedAt(d.getUpdatedAt())
                        .build());
            }
        }
    }

    /** If a partner somehow has two rows for one type, show the most recently updated one. */
    private static DeliveryDocument newer(DeliveryDocument a, DeliveryDocument b) {
        Instant ua = a.getUpdatedAt();
        Instant ub = b.getUpdatedAt();
        if (ua == null) return b;
        if (ub == null) return a;
        return ub.isAfter(ua) ? b : a;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private DeliveryKycReviewDto mapToDto(DeliveryPartnerProfile p) {
        String phone = userLookupService.findById(p.getUserId()).map(UserSummaryDto::getPhone).orElse(null);
        return DeliveryKycReviewDto.builder()
                .userId(p.getUserId())
                .fullName(p.getFullName())
                .phone(phone)
                .vehicleType(p.getVehicleType())
                .vehicleModel(p.getVehicleModel())
                .plateNumber(p.getPlateNumber())
                .licenseNumber(p.getLicenseNumber())
                .kycStatus(p.getKycStatus().name())
                .rejectionReason(p.getRejectionReason())
                .submittedAt(p.getUpdatedAt())
                .build();
    }
}
