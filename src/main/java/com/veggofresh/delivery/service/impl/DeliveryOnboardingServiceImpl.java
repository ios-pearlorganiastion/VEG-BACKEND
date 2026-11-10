package com.veggofresh.delivery.service.impl;

import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.dto.response.OnboardingApplicationResponseDto;
import com.veggofresh.delivery.dto.response.OnboardingNextAction;
import com.veggofresh.delivery.dto.response.OnboardingStatusResponseDto;
import com.veggofresh.delivery.entity.DeliveryApplicationState;
import com.veggofresh.delivery.entity.DeliveryDocument;
import com.veggofresh.delivery.entity.DeliveryDocumentStatus;
import com.veggofresh.delivery.entity.DeliveryDocumentType;
import com.veggofresh.delivery.entity.DeliveryKycReviewAction;
import com.veggofresh.delivery.entity.DeliveryKycReviewEntry;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryDocumentRepository;
import com.veggofresh.delivery.repository.DeliveryKycReviewHistoryRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.delivery.service.DeliveryApplicationGuard;
import com.veggofresh.delivery.service.DeliveryOnboardingService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rider onboarding.
 *
 * <p>The application has four states ({@link DeliveryApplicationState}): DRAFT, UNDER_REVIEW, APPROVED and
 * REJECTED. {@code kycStatus} is PENDING from the first save, so the real "sent for review" signal is
 * {@code applicationSubmittedAt}, set only by step 3. Step 3 is the final step and submits the application.
 *
 * <p>Details may only be edited in DRAFT and REJECTED; every edit path checks {@link DeliveryApplicationGuard}.
 * The progress counter ({@code verificationStep}) only moves forward, so a rider can always go back and fix an
 * earlier screen: each step can be saved again, and the document photo is only demanded when none is on file.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryOnboardingServiceImpl implements DeliveryOnboardingService {

    private final DeliveryPartnerProfileRepository profileRepository;
    private final DeliveryDocumentRepository documentRepository;
    private final DeliveryKycReviewHistoryRepository historyRepository;
    private final CloudinaryService cloudinaryService;

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public OnboardingStatusResponseDto getStatus(UUID userId) {
        // Read-only: a rider who has not saved anything yet is simply an empty draft, nothing is created.
        return mapToStatusDto(findOrBlank(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingApplicationResponseDto getApplication(UUID userId) {
        DeliveryPartnerProfile profile = findOrBlank(userId);
        Map<DeliveryDocumentType, DeliveryDocument> documents = documentsByType(userId);

        return OnboardingApplicationResponseDto.builder()
                .status(mapToStatusDto(profile))
                .basicInfo(OnboardingApplicationResponseDto.BasicInfo.builder()
                        .fullName(profile.getFullName())
                        .cityOfOperation(profile.getCityOfOperation())
                        .build())
                .license(OnboardingApplicationResponseDto.License.builder()
                        .licenseNumber(profile.getLicenseNumber())
                        .document(documentInfo(documents.get(DeliveryDocumentType.LICENSE)))
                        .build())
                .vehicle(OnboardingApplicationResponseDto.Vehicle.builder()
                        .vehicleType(profile.getVehicleType())
                        .plateNumber(profile.getPlateNumber())
                        .vehicleModel(profile.getVehicleModel())
                        .manufactureYear(profile.getManufactureYear())
                        .insurance(documentInfo(documents.get(DeliveryDocumentType.INSURANCE)))
                        .build())
                .bank(OnboardingApplicationResponseDto.Bank.builder()
                        .bankName(profile.getBankName())
                        .accountHolderName(profile.getAccountHolderName())
                        .accountNumber(profile.getAccountNumber())
                        .ifscCode(profile.getIfscCode())
                        .agreedToPayoutTerms(profile.isAgreedToPayoutTerms())
                        .proof(documentInfo(documents.get(DeliveryDocumentType.BANK_INFO)))
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @Override
    public OnboardingStatusResponseDto submitBasicInfo(UUID userId, BasicInfoRequestDto request) {
        DeliveryPartnerProfile profile = getOrCreate(userId);
        DeliveryApplicationGuard.requireEditable(profile, "name and city");

        profile.setFullName(request.getFullName().trim());
        profile.setCityOfOperation(request.getCityOfOperation().trim());
        profile.setHasBasicInfo(true);
        profileRepository.save(profile);
        return mapToStatusDto(profile);
    }

    @Override
    public OnboardingStatusResponseDto submitVerificationStep1(UUID userId, String licenseNumber, MultipartFile licensePhoto) {
        DeliveryPartnerProfile profile = getOrCreate(userId);
        DeliveryApplicationGuard.requireEditable(profile, "license");
        requireBasicInfoDone(profile);

        String license = requireText(licenseNumber, "License number");
        boolean newPhoto = requireDocument(userId, DeliveryDocumentType.LICENSE, licensePhoto, "License photo is required");

        if (newPhoto) {
            uploadDocument(userId, DeliveryDocumentType.LICENSE, licensePhoto);
        }
        profile.setLicenseNumber(license);
        profile.setVerificationStep(Math.max(profile.getVerificationStep(), 1));
        profileRepository.save(profile);
        return mapToStatusDto(profile);
    }

    @Override
    public OnboardingStatusResponseDto submitVerificationStep2(UUID userId, String vehicleType, String plateNumber,
                                                                 String vehicleModel, Integer manufactureYear,
                                                                 MultipartFile insurancePhoto) {
        DeliveryPartnerProfile profile = getOrCreate(userId);
        DeliveryApplicationGuard.requireEditable(profile, "vehicle details");
        requireStepAtLeast(profile, 1, "Complete Step 1 (license) before Step 2");

        String type = requireText(vehicleType, "Vehicle type");
        String plate = requireText(plateNumber, "Plate number");
        String model = requireText(vehicleModel, "Vehicle model");
        if (manufactureYear == null) {
            throw new BusinessException("ONBOARDING_FIELD_REQUIRED", "Manufacture year is required", HttpStatus.BAD_REQUEST);
        }
        boolean newPhoto = requireDocument(userId, DeliveryDocumentType.INSURANCE, insurancePhoto, "Insurance document is required");

        if (newPhoto) {
            uploadDocument(userId, DeliveryDocumentType.INSURANCE, insurancePhoto);
        }
        profile.setVehicleType(type);
        profile.setPlateNumber(plate);
        profile.setVehicleModel(model);
        profile.setManufactureYear(manufactureYear);
        profile.setVerificationStep(Math.max(profile.getVerificationStep(), 2));
        profileRepository.save(profile);
        return mapToStatusDto(profile);
    }

    /**
     * The final step: bank details plus the bank proof document. Saving it SUBMITS the application: it records
     * the submission time (which is what puts the rider in the admin's review queue), counts the submission and
     * writes a history line. Resubmitting after a rejection clears the old rejection from the profile; it stays
     * in the history.
     */
    @Override
    public OnboardingStatusResponseDto submitVerificationStep3(UUID userId, VerificationStep3RequestDto request,
                                                                 MultipartFile bankProof) {
        DeliveryPartnerProfile profile = getOrCreate(userId);
        DeliveryApplicationGuard.requireEditable(profile, "bank details");
        requireStepAtLeast(profile, 2, "Complete Step 2 (vehicle details) before Step 3");

        String bankName = requireText(request.getBankName(), "Bank name");
        String holder = requireText(request.getAccountHolderName(), "Account holder name");
        String accountNumber = requireText(request.getAccountNumber(), "Account number");
        String ifsc = requireText(request.getIfscCode(), "IFSC/routing code");
        if (!request.isAgreedToPayoutTerms()) {
            throw new BusinessException("ONBOARDING_TERMS_REQUIRED",
                    "You must agree to the payout terms to finish onboarding", HttpStatus.BAD_REQUEST);
        }
        boolean newProof = requireDocument(userId, DeliveryDocumentType.BANK_INFO, bankProof,
                "Bank proof document is required");
        // The earlier steps' documents must still be on file before anything is sent for review.
        requireDocumentOnFile(userId, DeliveryDocumentType.LICENSE, "License photo is missing - go back to Step 1");
        requireDocumentOnFile(userId, DeliveryDocumentType.INSURANCE, "Insurance document is missing - go back to Step 2");

        if (newProof) {
            uploadDocument(userId, DeliveryDocumentType.BANK_INFO, bankProof);
        }
        profile.setBankName(bankName);
        profile.setAccountHolderName(holder);
        profile.setAccountNumber(accountNumber);
        profile.setIfscCode(ifsc);
        profile.setAgreedToPayoutTerms(true);
        profile.setVerificationStep(3);

        // Submit.
        profile.setApplicationSubmittedAt(Instant.now());
        profile.setSubmissionCount(profile.getSubmissionCount() + 1);
        profile.setKycStatus(DeliveryKycStatus.PENDING);
        profile.setRejectionReason(null);
        profile.setRejectedSections(null);
        profileRepository.save(profile);

        historyRepository.save(DeliveryKycReviewEntry.of(userId, DeliveryKycReviewAction.SUBMITTED,
                profile.getSubmissionCount(), null, null, null));
        return mapToStatusDto(profile);
    }

    @Override
    public OnboardingStatusResponseDto withdraw(UUID userId) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND",
                        "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        if (DeliveryApplicationState.of(profile) != DeliveryApplicationState.UNDER_REVIEW) {
            throw new BusinessException("ONBOARDING_NOT_UNDER_REVIEW",
                    "Only an application that is under review can be withdrawn", HttpStatus.CONFLICT);
        }

        // Back to a draft: out of the review queue, parked on the final step so the rider can fix and resubmit.
        profile.setApplicationSubmittedAt(null);
        profile.setVerificationStep(2);
        profileRepository.save(profile);

        historyRepository.save(DeliveryKycReviewEntry.of(userId, DeliveryKycReviewAction.WITHDRAWN,
                profile.getSubmissionCount(), null, null, null));
        return mapToStatusDto(profile);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Shared with the standalone document-vault endpoint's storage logic -- both write
     * into the same delivery_documents table, so a re-upload here (e.g. resubmitting a
     * license photo) correctly replaces whatever was uploaded through the vault too,
     * and vice versa. Old Cloudinary asset is auto-deleted once the new one succeeds.
     */
    private void uploadDocument(UUID userId, DeliveryDocumentType type, MultipartFile file) {
        DeliveryDocument doc = documentRepository.findByDeliveryPartnerUserIdAndDocumentType(userId, type)
                .orElseGet(() -> {
                    DeliveryDocument newDoc = new DeliveryDocument();
                    newDoc.setDeliveryPartnerUserId(userId);
                    newDoc.setDocumentType(type);
                    return newDoc;
                });

        CloudinaryUploadResult upload = cloudinaryService.uploadDocument(
                file, "veggofresh/delivery-documents/" + userId + "/" + type.name());

        String oldPublicId = doc.getPublicId();
        doc.setFileUrl(upload.url());
        doc.setPublicId(upload.publicId());
        doc.setStatus(DeliveryDocumentStatus.PENDING);
        documentRepository.save(doc);

        cloudinaryService.deleteQuietly(oldPublicId);
    }

    /**
     * A new file is only demanded when none is on file yet. Returns true when the caller supplied a new file
     * that should be uploaded. Validation happens before any upload so a rejected request leaves nothing behind.
     */
    private boolean requireDocument(UUID userId, DeliveryDocumentType type, MultipartFile file, String missingMessage) {
        boolean supplied = file != null && !file.isEmpty();
        if (!supplied && !hasDocumentOnFile(userId, type)) {
            throw new BusinessException("ONBOARDING_DOCUMENT_REQUIRED", missingMessage, HttpStatus.BAD_REQUEST);
        }
        return supplied;
    }

    private void requireDocumentOnFile(UUID userId, DeliveryDocumentType type, String missingMessage) {
        if (!hasDocumentOnFile(userId, type)) {
            throw new BusinessException("ONBOARDING_DOCUMENT_REQUIRED", missingMessage, HttpStatus.BAD_REQUEST);
        }
    }

    private boolean hasDocumentOnFile(UUID userId, DeliveryDocumentType type) {
        return documentRepository.findByDeliveryPartnerUserIdAndDocumentType(userId, type)
                .map(d -> d.getFileUrl() != null && !d.getFileUrl().isBlank())
                .orElse(false);
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

    private static OnboardingApplicationResponseDto.DocumentInfo documentInfo(DeliveryDocument doc) {
        boolean uploaded = doc != null && doc.getFileUrl() != null && !doc.getFileUrl().isBlank();
        return OnboardingApplicationResponseDto.DocumentInfo.builder()
                .uploaded(uploaded)
                .fileUrl(uploaded ? doc.getFileUrl() : null)
                .status(uploaded && doc.getStatus() != null ? doc.getStatus().name() : null)
                .build();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("ONBOARDING_FIELD_REQUIRED", fieldName + " is required", HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    private DeliveryPartnerProfile getOrCreate(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    DeliveryPartnerProfile newProfile = new DeliveryPartnerProfile();
                    newProfile.setUserId(userId);
                    return profileRepository.save(newProfile);
                });
    }

    /** For read paths: the saved profile, or an empty in-memory draft that is never saved. */
    private DeliveryPartnerProfile findOrBlank(UUID userId) {
        return profileRepository.findByUserId(userId).orElseGet(DeliveryPartnerProfile::new);
    }

    private void requireBasicInfoDone(DeliveryPartnerProfile profile) {
        if (!profile.isHasBasicInfo()) {
            throw new BusinessException("ONBOARDING_BASIC_INFO_REQUIRED", "Submit basic info before starting verification", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireStepAtLeast(DeliveryPartnerProfile profile, int minStep, String message) {
        if (profile.getVerificationStep() < minStep) {
            throw new BusinessException("ONBOARDING_STEP_OUT_OF_ORDER", message, HttpStatus.BAD_REQUEST);
        }
    }

    private OnboardingStatusResponseDto mapToStatusDto(DeliveryPartnerProfile profile) {
        DeliveryApplicationState state = DeliveryApplicationState.of(profile);

        OnboardingNextAction nextAction;
        switch (state) {
            case APPROVED -> nextAction = OnboardingNextAction.DASHBOARD;
            case REJECTED -> nextAction = OnboardingNextAction.REJECTED;
            case UNDER_REVIEW -> nextAction = OnboardingNextAction.UNDER_REVIEW;
            default -> {
                // DRAFT: continue where the rider left off.
                if (!profile.isHasBasicInfo()) {
                    nextAction = OnboardingNextAction.BASIC_INFO;
                } else if (profile.getVerificationStep() < 1) {
                    nextAction = OnboardingNextAction.VERIFICATION_STEP_1;
                } else if (profile.getVerificationStep() < 2) {
                    nextAction = OnboardingNextAction.VERIFICATION_STEP_2;
                } else {
                    nextAction = OnboardingNextAction.VERIFICATION_STEP_3;
                }
            }
        }

        boolean rejected = state == DeliveryApplicationState.REJECTED;
        return OnboardingStatusResponseDto.builder()
                .hasBasicInfo(profile.isHasBasicInfo())
                .verificationStep(profile.getVerificationStep())
                .isSubmitted(state != DeliveryApplicationState.DRAFT)
                .kycStatus(profile.getKycStatus())
                .nextAction(nextAction)
                .applicationState(state)
                .submittedAt(profile.getApplicationSubmittedAt())
                .submissionCount(profile.getSubmissionCount())
                .rejectionCount(profile.getRejectionCount())
                .rejectionReason(rejected ? profile.getRejectionReason() : null)
                .rejectedSections(rejected ? DeliveryKycSection.fromCsv(profile.getRejectedSections()) : null)
                .canEdit(state.isEditable())
                .canWithdraw(state == DeliveryApplicationState.UNDER_REVIEW)
                .build();
    }
}
