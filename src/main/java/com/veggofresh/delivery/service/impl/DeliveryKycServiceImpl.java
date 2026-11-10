package com.veggofresh.delivery.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.dto.DeliveryKycBankDto;
import com.veggofresh.delivery.dto.DeliveryKycDetailDto;
import com.veggofresh.delivery.dto.DeliveryKycDocumentDto;
import com.veggofresh.delivery.dto.DeliveryKycEmergencyContactDto;
import com.veggofresh.delivery.dto.DeliveryKycHistoryDto;
import com.veggofresh.delivery.dto.DeliveryKycReviewDto;
import com.veggofresh.delivery.entity.DeliveryApplicationState;
import com.veggofresh.delivery.entity.DeliveryDocument;
import com.veggofresh.delivery.entity.DeliveryDocumentType;
import com.veggofresh.delivery.entity.DeliveryKycReviewAction;
import com.veggofresh.delivery.entity.DeliveryKycReviewEntry;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryDocumentRepository;
import com.veggofresh.delivery.repository.DeliveryKycReviewHistoryRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.delivery.service.DeliveryKycService;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.platform.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin review of rider applications.
 *
 * <p>An application is only reviewable while it is UNDER_REVIEW (submitted and still pending). The queue lists
 * exactly those, oldest first, and approve / reject refuse anything else. Every submit, withdraw, approve and
 * reject is written to the rider's history, so the admin can see an application was rejected once or several
 * times and why. The rider is notified of every decision.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryKycServiceImpl implements DeliveryKycService {

    /** The profile column holding the reason is 500 characters. */
    private static final int MAX_REASON_LENGTH = 500;

    /** Status shown for a document type the partner has not uploaded yet. */
    private static final String NOT_UPLOADED = "NOT_UPLOADED";

    private final DeliveryPartnerProfileRepository partnerRepository;
    private final DeliveryDocumentRepository documentRepository;
    private final DeliveryKycReviewHistoryRepository historyRepository;
    private final UserLookupService userLookupService;
    private final NotificationService notificationService;

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryKycReviewDto> listPendingKyc(Pageable pageable) {
        // First come, first served: whoever has waited longest is at the top.
        Pageable oldestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "applicationSubmittedAt"));
        return partnerRepository
                .findByKycStatusAndApplicationSubmittedAtIsNotNull(DeliveryKycStatus.PENDING, oldestFirst)
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

        List<DeliveryKycHistoryDto> history = historyRepository
                .findByDeliveryPartnerUserIdOrderByCreatedAtAscIdAsc(p.getUserId()).stream()
                .map(DeliveryKycServiceImpl::mapHistory)
                .collect(Collectors.toList());

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
                .submittedAt(p.getApplicationSubmittedAt())
                // ---- application state and history ----
                .applicationState(DeliveryApplicationState.of(p).name())
                .submissionCount(p.getSubmissionCount())
                .rejectionCount(p.getRejectionCount())
                .resubmission(p.getRejectionCount() > 0)
                .rejectedSections(DeliveryKycSection.fromCsv(p.getRejectedSections()).stream()
                        .map(Enum::name).collect(Collectors.toList()))
                .history(history)
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
    public void approveKyc(UUID adminUserId, UUID userId) {
        DeliveryPartnerProfile partner = getPartner(userId);
        requireUnderReview(partner);

        partner.setKycStatus(DeliveryKycStatus.APPROVED);
        partner.setRejectionReason(null);
        partner.setRejectedSections(null);
        partnerRepository.save(partner);

        historyRepository.save(DeliveryKycReviewEntry.of(userId, DeliveryKycReviewAction.APPROVED,
                partner.getSubmissionCount(), null, null, adminUserId));

        notificationService.send(userId, NotificationRecipientRole.DELIVERY, NotificationType.DELIVERY_KYC_APPROVED,
                "You're approved!",
                "Your delivery partner application has been approved. You can now go online and start accepting deliveries.",
                "{\"kycStatus\":\"APPROVED\"}");
    }

    @Override
    public void rejectKyc(UUID adminUserId, UUID userId, String reason, List<DeliveryKycSection> sections) {
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new BusinessException("KYC_REJECTION_REASON_REQUIRED",
                    "Tell the rider why the application was rejected", HttpStatus.BAD_REQUEST);
        }
        if (cleanReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("KYC_REJECTION_REASON_TOO_LONG",
                    "The rejection reason can be at most " + MAX_REASON_LENGTH + " characters", HttpStatus.BAD_REQUEST);
        }

        DeliveryPartnerProfile partner = getPartner(userId);
        requireUnderReview(partner);

        String sectionsCsv = DeliveryKycSection.toCsv(sections);
        partner.setKycStatus(DeliveryKycStatus.REJECTED);
        partner.setRejectionReason(cleanReason);
        partner.setRejectedSections(sectionsCsv);
        partner.setRejectionCount(partner.getRejectionCount() + 1);
        partnerRepository.save(partner);

        historyRepository.save(DeliveryKycReviewEntry.of(userId, DeliveryKycReviewAction.REJECTED,
                partner.getSubmissionCount(), cleanReason, sectionsCsv, adminUserId));

        String fix = sectionsCsv == null ? "your details" : describe(DeliveryKycSection.fromCsv(sectionsCsv));
        String dataJson = sectionsCsv == null
                ? "{\"kycStatus\":\"REJECTED\"}"
                : "{\"kycStatus\":\"REJECTED\",\"sections\":[\""
                        + String.join("\",\"", sectionsCsv.split(",")) + "\"]}";
        notificationService.send(userId, NotificationRecipientRole.DELIVERY, NotificationType.DELIVERY_KYC_REJECTED,
                "Your application needs changes",
                "Your application was not approved: " + cleanReason + ". Please update " + fix + " and submit again.",
                dataJson);
    }

    private DeliveryPartnerProfile getPartner(UUID userId) {
        return partnerRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));
    }

    /** A decision only makes sense for an application that was submitted and has not been decided yet. */
    private void requireUnderReview(DeliveryPartnerProfile partner) {
        DeliveryApplicationState state = DeliveryApplicationState.of(partner);
        if (state != DeliveryApplicationState.UNDER_REVIEW) {
            throw new BusinessException("KYC_NOT_UNDER_REVIEW",
                    "This application is not waiting for review (current state: " + state + ")", HttpStatus.CONFLICT);
        }
    }

    private static String describe(List<DeliveryKycSection> sections) {
        return sections.stream().map(s -> switch (s) {
            case BASIC_INFO -> "your basic info";
            case LICENSE -> "your license";
            case VEHICLE -> "your vehicle details";
            case BANK -> "your bank details";
        }).collect(Collectors.joining(", "));
    }

    private static DeliveryKycHistoryDto mapHistory(DeliveryKycReviewEntry e) {
        return DeliveryKycHistoryDto.builder()
                .action(e.getAction().name())
                .submissionNumber(e.getSubmissionNumber())
                .reason(e.getReason())
                .sections(DeliveryKycSection.fromCsv(e.getSections()).stream().map(Enum::name).collect(Collectors.toList()))
                .adminUserId(e.getAdminUserId())
                .at(e.getCreatedAt())
                .build();
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
                .submittedAt(p.getApplicationSubmittedAt())
                .submissionCount(p.getSubmissionCount())
                .rejectionCount(p.getRejectionCount())
                .resubmission(p.getRejectionCount() > 0)
                .build();
    }
}
