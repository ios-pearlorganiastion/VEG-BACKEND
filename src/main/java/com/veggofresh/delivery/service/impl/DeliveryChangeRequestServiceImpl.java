package com.veggofresh.delivery.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.dto.AdminDeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.DeliveryChangeOverviewDto;
import com.veggofresh.delivery.dto.DeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.entity.DeliveryApplicationState;
import com.veggofresh.delivery.entity.DeliveryChangeRequest;
import com.veggofresh.delivery.entity.DeliveryChangeRequestStatus;
import com.veggofresh.delivery.entity.DeliveryDocument;
import com.veggofresh.delivery.entity.DeliveryDocumentStatus;
import com.veggofresh.delivery.entity.DeliveryDocumentType;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryChangeRequestRepository;
import com.veggofresh.delivery.repository.DeliveryDocumentRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.delivery.service.DeliveryChangeRequestService;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.payment.entity.PayoutRequestStatus;
import com.veggofresh.payment.repository.PayoutRequestRepository;
import com.veggofresh.payment.repository.UserBankAccountRepository;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reviewed changes to verified details after a rider is approved.
 *
 * <p>A change is stored as a PENDING {@link DeliveryChangeRequest} and never touches the profile until an admin
 * approves it, so the rider keeps working on the old verified details meanwhile. Each section needs its evidence
 * (license photo, insurance document, bank proof) and a rider can have only one open request per section.
 *
 * <p>Bank changes get extra care, because they decide where money goes:
 * <ul>
 *   <li>approval is refused while the rider has payout requests waiting for approval (they read the bank details
 *       only when an admin approves them, so they would be sent to the new account);</li>
 *   <li>approval also resets the cached Razorpay fund account, otherwise payouts would keep going to the old bank.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryChangeRequestServiceImpl implements DeliveryChangeRequestService {

    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final int MAX_REASON_LENGTH = 500;

    private final DeliveryPartnerProfileRepository profileRepository;
    private final DeliveryDocumentRepository documentRepository;
    private final DeliveryChangeRequestRepository changeRequestRepository;
    private final UserBankAccountRepository userBankAccountRepository;
    private final PayoutRequestRepository payoutRequestRepository;
    private final CloudinaryService cloudinaryService;
    private final NotificationService notificationService;
    private final UserLookupService userLookupService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Rider: reads
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public DeliveryChangeOverviewDto getOverview(UUID userId) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId).orElseGet(DeliveryPartnerProfile::new);
        List<DeliveryChangeRequest> all = changeRequestRepository.findByDeliveryPartnerUserIdOrderByCreatedAtDescIdDesc(userId);
        Map<DeliveryDocumentType, DeliveryDocument> documents = documentsByType(userId);

        List<DeliveryChangeOverviewDto.SectionOverview> sections = new ArrayList<>();
        for (DeliveryKycSection section : DeliveryKycSection.values()) {
            List<DeliveryChangeRequest> mine = all.stream()
                    .filter(r -> r.getSection() == section)
                    .collect(Collectors.toList()); // already newest first

            DeliveryChangeRequest pending = mine.stream()
                    .filter(r -> r.getStatus() == DeliveryChangeRequestStatus.PENDING).findFirst().orElse(null);
            // The latest decided request tells whether the last word was a refusal. Cancelled ones do not count.
            DeliveryChangeRequest lastDecided = mine.stream()
                    .filter(r -> r.getStatus() == DeliveryChangeRequestStatus.APPROVED
                            || r.getStatus() == DeliveryChangeRequestStatus.REJECTED)
                    .findFirst().orElse(null);

            String state;
            if (pending != null) {
                state = "UNDER_REVIEW";
            } else if (lastDecided != null && lastDecided.getStatus() == DeliveryChangeRequestStatus.REJECTED) {
                state = "REJECTED";
            } else {
                state = "VERIFIED";
            }

            DeliveryDocumentType docType = documentTypeFor(section);
            sections.add(DeliveryChangeOverviewDto.SectionOverview.builder()
                    .section(section)
                    .state(state)
                    .current(maskForRider(section, currentValues(profile, section)))
                    .documentUrl(docType == null ? null : liveDocumentUrl(documents.get(docType)))
                    .pendingRequest(pending == null ? null : DeliveryChangeOverviewDto.PendingRequest.builder()
                            .id(pending.getId())
                            .submittedAt(pending.getCreatedAt())
                            .requested(maskForRider(section, fromJson(pending.getRequestedValues())))
                            .documentUrl(pending.getDocumentUrl())
                            .build())
                    .lastRejection("REJECTED".equals(state) ? DeliveryChangeOverviewDto.Rejection.builder()
                            .id(lastDecided.getId())
                            .reason(lastDecided.getDecisionReason())
                            .decidedAt(lastDecided.getDecidedAt())
                            .build() : null)
                    .build());
        }

        return DeliveryChangeOverviewDto.builder()
                .canRequestChanges(DeliveryApplicationState.of(profile) == DeliveryApplicationState.APPROVED)
                .sections(sections)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryChangeRequestDto> getHistory(UUID userId) {
        return changeRequestRepository.findByDeliveryPartnerUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(this::toRiderDto)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Rider: submit / cancel
    // =========================================================================

    @Override
    public DeliveryChangeRequestDto submitBasicInfo(UUID userId, BasicInfoRequestDto request) {
        Map<String, String> requested = new LinkedHashMap<>();
        requested.put("fullName", requireText(request.getFullName(), "Full name"));
        requested.put("cityOfOperation", requireText(request.getCityOfOperation(), "City of operation"));
        return submit(userId, DeliveryKycSection.BASIC_INFO, requested, null, false, null);
    }

    @Override
    public DeliveryChangeRequestDto submitLicense(UUID userId, String licenseNumber, MultipartFile photo) {
        Map<String, String> requested = new LinkedHashMap<>();
        requested.put("licenseNumber", requireText(licenseNumber, "License number"));
        return submit(userId, DeliveryKycSection.LICENSE, requested, photo, true, "A photo of the new license is required");
    }

    @Override
    public DeliveryChangeRequestDto submitVehicle(UUID userId, String vehicleType, String plateNumber, String vehicleModel,
                                                  Integer manufactureYear, MultipartFile insurance) {
        Map<String, String> requested = new LinkedHashMap<>();
        requested.put("vehicleType", requireText(vehicleType, "Vehicle type"));
        requested.put("plateNumber", requireText(plateNumber, "Plate number"));
        requested.put("vehicleModel", requireText(vehicleModel, "Vehicle model"));
        if (manufactureYear == null) {
            throw new BusinessException("CHANGE_REQUEST_FIELD_REQUIRED", "Manufacture year is required", HttpStatus.BAD_REQUEST);
        }
        requested.put("manufactureYear", String.valueOf(manufactureYear));
        return submit(userId, DeliveryKycSection.VEHICLE, requested, insurance, true, "The insurance document for this vehicle is required");
    }

    @Override
    public DeliveryChangeRequestDto submitBank(UUID userId, VerificationStep3RequestDto bank, MultipartFile proof) {
        Map<String, String> requested = new LinkedHashMap<>();
        requested.put("bankName", requireText(bank.getBankName(), "Bank name"));
        requested.put("accountHolderName", requireText(bank.getAccountHolderName(), "Account holder name"));
        requested.put("accountNumber", requireText(bank.getAccountNumber(), "Account number"));
        requested.put("ifscCode", requireText(bank.getIfscCode(), "IFSC/routing code"));
        if (!bank.isAgreedToPayoutTerms()) {
            throw new BusinessException("CHANGE_REQUEST_TERMS_REQUIRED",
                    "You must agree to the payout terms to change your bank details", HttpStatus.BAD_REQUEST);
        }
        return submit(userId, DeliveryKycSection.BANK, requested, proof, true, "A bank proof document (passbook or cancelled cheque) is required");
    }

    private DeliveryChangeRequestDto submit(UUID userId, DeliveryKycSection section, Map<String, String> requested,
                                            MultipartFile file, boolean documentRequired, String documentMissingMessage) {
        DeliveryPartnerProfile profile = getProfile(userId);
        requireApproved(profile);

        if (changeRequestRepository.findByDeliveryPartnerUserIdAndSectionAndStatus(
                userId, section, DeliveryChangeRequestStatus.PENDING).isPresent()) {
            throw alreadyOpen();
        }

        Map<String, String> previous = currentValues(profile, section);
        if (section == DeliveryKycSection.BASIC_INFO && previous.equals(requested)) {
            throw new BusinessException("CHANGE_REQUEST_NO_CHANGE",
                    "These are the same as your current details", HttpStatus.BAD_REQUEST);
        }

        boolean hasFile = file != null && !file.isEmpty();
        if (documentRequired && !hasFile) {
            throw new BusinessException("CHANGE_REQUEST_DOCUMENT_REQUIRED", documentMissingMessage, HttpStatus.BAD_REQUEST);
        }

        // Everything is validated: only now is a file uploaded, so a refused request leaves nothing behind.
        CloudinaryUploadResult upload = hasFile
                ? cloudinaryService.uploadDocument(file, "veggofresh/delivery-change-requests/" + userId + "/" + section.name())
                : null;

        DeliveryChangeRequest request = new DeliveryChangeRequest();
        request.setDeliveryPartnerUserId(userId);
        request.setSection(section);
        request.setStatus(DeliveryChangeRequestStatus.PENDING);
        request.setPreviousValues(toJson(previous));
        request.setRequestedValues(toJson(requested));
        if (upload != null) {
            request.setDocumentUrl(upload.url());
            request.setDocumentPublicId(upload.publicId());
        }
        try {
            request = changeRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            // Two taps at once: the database's one-open-request-per-section rule caught the second.
            if (upload != null) {
                cloudinaryService.deleteQuietly(upload.publicId());
            }
            throw alreadyOpen();
        }
        return toRiderDto(request);
    }

    @Override
    public DeliveryChangeRequestDto cancel(UUID userId, UUID requestId) {
        DeliveryChangeRequest request = changeRequestRepository.findById(requestId)
                .filter(r -> r.getDeliveryPartnerUserId().equals(userId))
                .orElseThrow(() -> new BusinessException("CHANGE_REQUEST_NOT_FOUND", "Change request not found", HttpStatus.NOT_FOUND));
        requirePending(request);

        request.setStatus(DeliveryChangeRequestStatus.CANCELLED);
        request.setDecidedAt(Instant.now());
        String publicId = request.getDocumentPublicId();
        request.setDocumentUrl(null);
        request.setDocumentPublicId(null);
        changeRequestRepository.save(request);

        cloudinaryService.deleteQuietly(publicId); // the file was only ever held for this request
        return toRiderDto(request);
    }

    // =========================================================================
    // Admin
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Page<AdminDeliveryChangeRequestDto> list(DeliveryChangeRequestStatus status, DeliveryKycSection section,
                                                    UUID userId, Pageable pageable) {
        // No filters at all = the review queue.
        DeliveryChangeRequestStatus effective = (status == null && userId == null) ? DeliveryChangeRequestStatus.PENDING : status;
        // The queue is first come, first served; everything else reads newest first.
        Sort.Direction direction = effective == DeliveryChangeRequestStatus.PENDING ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(direction, "createdAt"));

        Page<DeliveryChangeRequest> page = userId == null
                ? changeRequestRepository.findWithFilters(effective, section, sorted)
                : changeRequestRepository.findForPartnerWithFilters(userId, effective, section, sorted);
        return page.map(this::toAdminDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDeliveryChangeRequestDto getDetail(UUID requestId) {
        return toAdminDto(getRequest(requestId));
    }

    @Override
    public void approve(UUID adminUserId, UUID requestId) {
        DeliveryChangeRequest request = getRequest(requestId);
        requirePending(request);

        UUID userId = request.getDeliveryPartnerUserId();
        DeliveryPartnerProfile profile = getProfile(userId);
        if (DeliveryApplicationState.of(profile) != DeliveryApplicationState.APPROVED) {
            throw new BusinessException("CHANGE_REQUEST_PARTNER_NOT_APPROVED",
                    "This rider's application is no longer approved", HttpStatus.CONFLICT);
        }

        Map<String, String> values = fromJson(request.getRequestedValues());
        switch (request.getSection()) {
            case BASIC_INFO -> {
                profile.setFullName(values.get("fullName"));
                profile.setCityOfOperation(values.get("cityOfOperation"));
            }
            case LICENSE -> {
                profile.setLicenseNumber(values.get("licenseNumber"));
                replaceDocument(userId, DeliveryDocumentType.LICENSE, request);
            }
            case VEHICLE -> {
                profile.setVehicleType(values.get("vehicleType"));
                profile.setPlateNumber(values.get("plateNumber"));
                profile.setVehicleModel(values.get("vehicleModel"));
                profile.setManufactureYear(Integer.valueOf(values.get("manufactureYear")));
                replaceDocument(userId, DeliveryDocumentType.INSURANCE, request);
            }
            case BANK -> {
                // Payout requests read the bank details only when an admin approves them. A change now would send
                // the ones still waiting to the NEW account, so they must be settled first.
                if (payoutRequestRepository.countByUserIdAndStatus(userId, PayoutRequestStatus.PENDING) > 0) {
                    throw new BusinessException("CHANGE_BLOCKED_PENDING_PAYOUTS",
                            "This rider has payout requests waiting for approval. Approve or reject them first, "
                                    + "so they are not sent to the new bank account.",
                            HttpStatus.CONFLICT);
                }
                profile.setBankName(values.get("bankName"));
                profile.setAccountHolderName(values.get("accountHolderName"));
                profile.setAccountNumber(values.get("accountNumber"));
                profile.setIfscCode(values.get("ifscCode"));
                profile.setAgreedToPayoutTerms(true);
                replaceDocument(userId, DeliveryDocumentType.BANK_INFO, request);
                refreshPayoutAccount(userId, profile);
            }
        }
        profileRepository.save(profile);

        request.setStatus(DeliveryChangeRequestStatus.APPROVED);
        request.setDecidedBy(adminUserId);
        request.setDecidedAt(Instant.now());
        changeRequestRepository.save(request);

        notificationService.send(userId, NotificationRecipientRole.DELIVERY, NotificationType.DELIVERY_CHANGE_APPROVED,
                "Change approved",
                "Your " + label(request.getSection()) + " change has been approved and is now active.",
                dataJson(request, "APPROVED"));
    }

    @Override
    public void reject(UUID adminUserId, UUID requestId, String reason) {
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new BusinessException("CHANGE_REJECTION_REASON_REQUIRED",
                    "Tell the rider why the change was refused", HttpStatus.BAD_REQUEST);
        }
        if (cleanReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("CHANGE_REJECTION_REASON_TOO_LONG",
                    "The reason can be at most " + MAX_REASON_LENGTH + " characters", HttpStatus.BAD_REQUEST);
        }

        DeliveryChangeRequest request = getRequest(requestId);
        requirePending(request);

        request.setStatus(DeliveryChangeRequestStatus.REJECTED);
        request.setDecisionReason(cleanReason);
        request.setDecidedBy(adminUserId);
        request.setDecidedAt(Instant.now());
        changeRequestRepository.save(request);
        // The attached file is kept on purpose so the refusal can be looked at again later.

        notificationService.send(request.getDeliveryPartnerUserId(), NotificationRecipientRole.DELIVERY,
                NotificationType.DELIVERY_CHANGE_REJECTED,
                "Change not approved",
                "Your " + label(request.getSection()) + " change was not approved: " + cleanReason
                        + ". Your previous details are unchanged. You can send a new request.",
                dataJson(request, "REJECTED"));
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** The new document goes live in place of the old one; the old Cloudinary asset is removed. */
    private void replaceDocument(UUID userId, DeliveryDocumentType type, DeliveryChangeRequest request) {
        if (request.getDocumentUrl() == null) {
            return;
        }
        DeliveryDocument doc = documentRepository.findByDeliveryPartnerUserIdAndDocumentType(userId, type)
                .orElseGet(() -> {
                    DeliveryDocument created = new DeliveryDocument();
                    created.setDeliveryPartnerUserId(userId);
                    created.setDocumentType(type);
                    return created;
                });
        String oldPublicId = doc.getPublicId();
        doc.setFileUrl(request.getDocumentUrl());
        doc.setPublicId(request.getDocumentPublicId());
        doc.setStatus(DeliveryDocumentStatus.VERIFIED); // an admin just reviewed it
        documentRepository.save(doc);

        if (oldPublicId != null && !oldPublicId.equals(request.getDocumentPublicId())) {
            cloudinaryService.deleteQuietly(oldPublicId);
        }
    }

    /**
     * Brings the payout account in line with the new bank details right away and drops the cached Razorpay fund
     * account, so the next payout creates a fresh one for the new account instead of going to the old bank.
     */
    private void refreshPayoutAccount(UUID userId, DeliveryPartnerProfile profile) {
        userBankAccountRepository.findByUserId(userId).ifPresent(account -> {
            account.setAccountHolderName(profile.getAccountHolderName());
            account.setAccountNumber(profile.getAccountNumber());
            account.setIfscCode(profile.getIfscCode());
            account.setBankName(profile.getBankName());
            account.setRazorpayFundAccountId(null);
            userBankAccountRepository.save(account);
        });
    }

    private void requireApproved(DeliveryPartnerProfile profile) {
        DeliveryApplicationState state = DeliveryApplicationState.of(profile);
        if (state == DeliveryApplicationState.APPROVED) {
            return;
        }
        String message = state == DeliveryApplicationState.UNDER_REVIEW
                ? "Your application is still under review. Withdraw it first if you need to change something."
                : "Your application is not approved yet, so you can edit it directly instead of sending a change request.";
        throw new BusinessException("CHANGE_REQUEST_NOT_AVAILABLE", message, HttpStatus.CONFLICT);
    }

    private void requirePending(DeliveryChangeRequest request) {
        if (request.getStatus() != DeliveryChangeRequestStatus.PENDING) {
            throw new BusinessException("CHANGE_REQUEST_NOT_PENDING",
                    "This change request is already " + request.getStatus(), HttpStatus.CONFLICT);
        }
    }

    private BusinessException alreadyOpen() {
        return new BusinessException("CHANGE_REQUEST_ALREADY_OPEN",
                "You already have a change under review for this section. Cancel it first to send a new one.",
                HttpStatus.CONFLICT);
    }

    private DeliveryPartnerProfile getProfile(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));
    }

    private DeliveryChangeRequest getRequest(UUID requestId) {
        return changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("CHANGE_REQUEST_NOT_FOUND", "Change request not found", HttpStatus.NOT_FOUND));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("CHANGE_REQUEST_FIELD_REQUIRED", fieldName + " is required", HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    private static DeliveryDocumentType documentTypeFor(DeliveryKycSection section) {
        return switch (section) {
            case BASIC_INFO -> null;
            case LICENSE -> DeliveryDocumentType.LICENSE;
            case VEHICLE -> DeliveryDocumentType.INSURANCE;
            case BANK -> DeliveryDocumentType.BANK_INFO;
        };
    }

    private Map<DeliveryDocumentType, DeliveryDocument> documentsByType(UUID userId) {
        Map<DeliveryDocumentType, DeliveryDocument> byType = new EnumMap<>(DeliveryDocumentType.class);
        for (DeliveryDocument d : documentRepository.findByDeliveryPartnerUserId(userId)) {
            if (d.getDocumentType() != null) {
                byType.put(d.getDocumentType(), d);
            }
        }
        return byType;
    }

    private static String liveDocumentUrl(DeliveryDocument doc) {
        return doc != null && doc.getFileUrl() != null && !doc.getFileUrl().isBlank() ? doc.getFileUrl() : null;
    }

    /** The profile's live values for a section, as a map of strings (absent values are left out). */
    private static Map<String, String> currentValues(DeliveryPartnerProfile p, DeliveryKycSection section) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (section) {
            case BASIC_INFO -> {
                put(values, "fullName", p.getFullName());
                put(values, "cityOfOperation", p.getCityOfOperation());
            }
            case LICENSE -> put(values, "licenseNumber", p.getLicenseNumber());
            case VEHICLE -> {
                put(values, "vehicleType", p.getVehicleType());
                put(values, "plateNumber", p.getPlateNumber());
                put(values, "vehicleModel", p.getVehicleModel());
                put(values, "manufactureYear", p.getManufactureYear() == null ? null : String.valueOf(p.getManufactureYear()));
            }
            case BANK -> {
                put(values, "bankName", p.getBankName());
                put(values, "accountHolderName", p.getAccountHolderName());
                put(values, "accountNumber", p.getAccountNumber());
                put(values, "ifscCode", p.getIfscCode());
            }
        }
        return values;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** The rider sees their own account number masked; everything else is shown as is. */
    private static Map<String, String> maskForRider(DeliveryKycSection section, Map<String, String> values) {
        if (section != DeliveryKycSection.BANK || !values.containsKey("accountNumber")) {
            return values;
        }
        Map<String, String> masked = new LinkedHashMap<>(values);
        String number = masked.get("accountNumber");
        masked.put("accountNumber", number.length() <= 4 ? "****" : "****" + number.substring(number.length() - 4));
        return masked;
    }

    private static String label(DeliveryKycSection section) {
        return switch (section) {
            case BASIC_INFO -> "name and city";
            case LICENSE -> "license";
            case VEHICLE -> "vehicle details";
            case BANK -> "bank details";
        };
    }

    private static String dataJson(DeliveryChangeRequest request, String status) {
        return "{\"section\":\"" + request.getSection().name() + "\",\"status\":\"" + status
                + "\",\"requestId\":\"" + request.getId() + "\"}";
    }

    private DeliveryChangeRequestDto toRiderDto(DeliveryChangeRequest r) {
        return DeliveryChangeRequestDto.builder()
                .id(r.getId())
                .section(r.getSection())
                .status(r.getStatus())
                .requested(maskForRider(r.getSection(), fromJson(r.getRequestedValues())))
                .documentUrl(r.getDocumentUrl())
                .submittedAt(r.getCreatedAt())
                .decidedAt(r.getDecidedAt())
                .reason(r.getDecisionReason())
                .build();
    }

    private AdminDeliveryChangeRequestDto toAdminDto(DeliveryChangeRequest r) {
        UUID userId = r.getDeliveryPartnerUserId();
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId).orElse(null);
        String phone = userLookupService.findById(userId).map(UserSummaryDto::getPhone).orElse(null);

        DeliveryDocumentType docType = documentTypeFor(r.getSection());
        String currentDocumentUrl = docType == null ? null
                : documentRepository.findByDeliveryPartnerUserIdAndDocumentType(userId, docType)
                        .map(DeliveryChangeRequestServiceImpl::liveDocumentUrl).orElse(null);

        return AdminDeliveryChangeRequestDto.builder()
                .id(r.getId())
                .userId(userId)
                .fullName(profile == null ? null : profile.getFullName())
                .phone(phone)
                .section(r.getSection())
                .status(r.getStatus())
                .submittedAt(r.getCreatedAt())
                .decidedAt(r.getDecidedAt())
                .decidedBy(r.getDecidedBy())
                .reason(r.getDecisionReason())
                .previous(fromJson(r.getPreviousValues()))
                .current(profile == null ? new LinkedHashMap<>() : currentValues(profile, r.getSection()))
                .requested(fromJson(r.getRequestedValues()))
                .documentUrl(r.getDocumentUrl())
                .currentDocumentUrl(currentDocumentUrl)
                .pendingPayoutRequests(r.getSection() == DeliveryKycSection.BANK
                        ? (int) payoutRequestRepository.countByUserIdAndStatus(userId, PayoutRequestStatus.PENDING)
                        : null)
                .build();
    }

    private String toJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the change request values", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the change request values", e);
        }
    }
}
