package com.veggofresh.delivery.service;

import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.dto.response.OnboardingNextAction;
import com.veggofresh.delivery.dto.response.OnboardingStatusResponseDto;
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
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rider onboarding: a rider is only "submitted" after the final step, details are locked while under review and
 * once approved, a submitted application can be withdrawn, and a rejected one can be fixed and resubmitted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryOnboardingServiceTest {

    @Mock
    private DeliveryPartnerProfileRepository profileRepository;
    @Mock
    private DeliveryDocumentRepository documentRepository;
    @Mock
    private DeliveryKycReviewHistoryRepository historyRepository;
    @Mock
    private CloudinaryService cloudinaryService;

    private com.veggofresh.delivery.service.DeliveryOnboardingService service;
    private DeliveryPartnerProfile profile;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        service = instantiate();
        profile = new DeliveryPartnerProfile();
        profile.setUserId(userId);
        when(profileRepository.findByUserId(userId)).thenAnswer(i -> Optional.ofNullable(profile));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(cloudinaryService.uploadDocument(any(), anyString()))
                .thenReturn(new CloudinaryUploadResult("https://files/doc.jpg", "public-id"));
        when(documentRepository.findByDeliveryPartnerUserIdAndDocumentType(any(), any())).thenReturn(Optional.empty());
    }

    private com.veggofresh.delivery.service.DeliveryOnboardingService instantiate() throws Exception {
        Constructor<?> constructor = com.veggofresh.delivery.service.impl.DeliveryOnboardingServiceImpl.class
                .getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == DeliveryPartnerProfileRepository.class) {
                args[i] = profileRepository;
            } else if (types[i] == DeliveryDocumentRepository.class) {
                args[i] = documentRepository;
            } else if (types[i] == DeliveryKycReviewHistoryRepository.class) {
                args[i] = historyRepository;
            } else if (types[i] == CloudinaryService.class) {
                args[i] = cloudinaryService;
            }
        }
        constructor.setAccessible(true);
        return (com.veggofresh.delivery.service.DeliveryOnboardingService) constructor.newInstance(args);
    }

    private static MultipartFile photo() {
        return new MockMultipartFile("photo", "doc.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private void givenDocumentOnFile(DeliveryDocumentType type) {
        DeliveryDocument doc = new DeliveryDocument();
        doc.setDocumentType(type);
        doc.setFileUrl("https://files/" + type + ".jpg");
        when(documentRepository.findByDeliveryPartnerUserIdAndDocumentType(userId, type)).thenReturn(Optional.of(doc));
    }

    private static VerificationStep3RequestDto bank(boolean agreed) {
        VerificationStep3RequestDto r = new VerificationStep3RequestDto();
        r.setBankName("HDFC");
        r.setAccountHolderName("Ravi Kumar");
        r.setAccountNumber("1234567890");
        r.setIfscCode("HDFC0001234");
        r.setAgreedToPayoutTerms(agreed);
        return r;
    }

    /** A profile that has finished steps 1 and 2 (ready for the final step). */
    private void givenReadyForStep3() {
        profile.setHasBasicInfo(true);
        profile.setVerificationStep(2);
        givenDocumentOnFile(DeliveryDocumentType.LICENSE);
        givenDocumentOnFile(DeliveryDocumentType.INSURANCE);
    }

    private void givenSubmitted() {
        givenReadyForStep3();
        profile.setVerificationStep(3);
        profile.setApplicationSubmittedAt(Instant.now());
        profile.setSubmissionCount(1);
    }

    private BusinessException codeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run);
    }

    // ── the original problem ────────────────────────────────────────────────

    @Test
    @DisplayName("A rider who only filled basic info is a DRAFT, not submitted, even though kycStatus is PENDING")
    void basicInfoOnlyIsStillADraft() {
        BasicInfoRequestDto request = new BasicInfoRequestDto();
        request.setFullName("  Ravi Kumar ");
        request.setCityOfOperation("Dehradun");

        OnboardingStatusResponseDto status = service.submitBasicInfo(userId, request);

        assertEquals("Ravi Kumar", profile.getFullName());
        assertNull(profile.getVehicleType(), "vehicle type is no longer asked in basic info");
        assertNull(profile.getApplicationSubmittedAt());
        assertEquals(DeliveryKycStatus.PENDING, status.getKycStatus());
        assertEquals(DeliveryApplicationState.DRAFT, status.getApplicationState());
        assertFalse(status.isSubmitted());
        assertEquals(OnboardingNextAction.VERIFICATION_STEP_1, status.getNextAction());
        assertTrue(status.isCanEdit());
    }

    @Test
    @DisplayName("Status for a rider with no profile is an empty draft and saves nothing")
    void statusDoesNotCreateAProfile() {
        profile = null;

        OnboardingStatusResponseDto status = service.getStatus(userId);

        assertEquals(OnboardingNextAction.BASIC_INFO, status.getNextAction());
        assertEquals(DeliveryApplicationState.DRAFT, status.getApplicationState());
        verify(profileRepository, never()).save(any());
    }

    // ── steps ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Step 1 needs a photo only when none is on file")
    void step1PhotoOnlyWhenNoneOnFile() {
        profile.setHasBasicInfo(true);

        BusinessException ex = codeOf(() -> service.submitVerificationStep1(userId, "DL-123", null));
        assertEquals("ONBOARDING_DOCUMENT_REQUIRED", ex.getErrorCode());
        verify(cloudinaryService, never()).uploadDocument(any(), anyString());

        givenDocumentOnFile(DeliveryDocumentType.LICENSE);
        OnboardingStatusResponseDto status = service.submitVerificationStep1(userId, "DL-999", null);

        assertEquals("DL-999", profile.getLicenseNumber());
        assertEquals(1, profile.getVerificationStep());
        assertEquals(OnboardingNextAction.VERIFICATION_STEP_2, status.getNextAction());
        verify(cloudinaryService, never()).uploadDocument(any(), anyString());
    }

    @Test
    @DisplayName("Step 2 keeps the vehicle type with the other vehicle details and needs step 1 first")
    void step2HoldsTheVehicleType() {
        profile.setHasBasicInfo(true);

        BusinessException early = codeOf(() ->
                service.submitVerificationStep2(userId, "BIKE", "UK07AB1234", "Splendor", 2021, photo()));
        assertEquals("ONBOARDING_STEP_OUT_OF_ORDER", early.getErrorCode());

        profile.setVerificationStep(1);
        OnboardingStatusResponseDto status =
                service.submitVerificationStep2(userId, "BIKE", "UK07AB1234", "Splendor", 2021, photo());

        assertEquals("BIKE", profile.getVehicleType());
        assertEquals("UK07AB1234", profile.getPlateNumber());
        assertEquals("Splendor", profile.getVehicleModel());
        assertEquals(2021, profile.getManufactureYear());
        assertEquals(OnboardingNextAction.VERIFICATION_STEP_3, status.getNextAction());
        verify(cloudinaryService).uploadDocument(any(), anyString());
    }

    @Test
    @DisplayName("Step 2 refuses a blank vehicle type")
    void step2RequiresAVehicleType() {
        profile.setHasBasicInfo(true);
        profile.setVerificationStep(1);

        BusinessException ex = codeOf(() ->
                service.submitVerificationStep2(userId, "  ", "UK07AB1234", "Splendor", 2021, photo()));

        assertEquals("ONBOARDING_FIELD_REQUIRED", ex.getErrorCode());
    }

    // ── final step = submit ─────────────────────────────────────────────────

    @Test
    @DisplayName("Step 3 submits: submission time, count, PENDING and a SUBMITTED history line")
    void step3Submits() {
        givenReadyForStep3();

        OnboardingStatusResponseDto status = service.submitVerificationStep3(userId, bank(true), photo());

        assertNotNull(profile.getApplicationSubmittedAt());
        assertEquals(1, profile.getSubmissionCount());
        assertEquals(3, profile.getVerificationStep());
        assertEquals(DeliveryKycStatus.PENDING, profile.getKycStatus());
        assertEquals("HDFC", profile.getBankName());
        assertEquals(DeliveryApplicationState.UNDER_REVIEW, status.getApplicationState());
        assertEquals(OnboardingNextAction.UNDER_REVIEW, status.getNextAction());
        assertTrue(status.isSubmitted());
        assertFalse(status.isCanEdit());
        assertTrue(status.isCanWithdraw());

        ArgumentCaptor<DeliveryKycReviewEntry> entry = ArgumentCaptor.forClass(DeliveryKycReviewEntry.class);
        verify(historyRepository).save(entry.capture());
        assertEquals(DeliveryKycReviewAction.SUBMITTED, entry.getValue().getAction());
        assertEquals(1, entry.getValue().getSubmissionNumber());
        verify(cloudinaryService).uploadDocument(any(), anyString());
    }

    @Test
    @DisplayName("Step 3 needs the payout terms and a bank proof, and nothing is submitted without them")
    void step3Requirements() {
        givenReadyForStep3();

        assertEquals("ONBOARDING_TERMS_REQUIRED",
                codeOf(() -> service.submitVerificationStep3(userId, bank(false), photo())).getErrorCode());
        assertEquals("ONBOARDING_DOCUMENT_REQUIRED",
                codeOf(() -> service.submitVerificationStep3(userId, bank(true), null)).getErrorCode());

        assertNull(profile.getApplicationSubmittedAt());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Step 3 cannot be reached before step 2")
    void step3NeedsStep2() {
        profile.setHasBasicInfo(true);
        profile.setVerificationStep(1);

        assertEquals("ONBOARDING_STEP_OUT_OF_ORDER",
                codeOf(() -> service.submitVerificationStep3(userId, bank(true), photo())).getErrorCode());
    }

    // ── locking ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("While under review nothing can be edited")
    void lockedUnderReview() {
        givenSubmitted();
        BasicInfoRequestDto request = new BasicInfoRequestDto();
        request.setFullName("Someone Else");
        request.setCityOfOperation("Delhi");

        assertEquals("ONBOARDING_LOCKED_UNDER_REVIEW",
                codeOf(() -> service.submitBasicInfo(userId, request)).getErrorCode());
        assertEquals("ONBOARDING_LOCKED_UNDER_REVIEW",
                codeOf(() -> service.submitVerificationStep1(userId, "DL-1", photo())).getErrorCode());
        assertEquals("ONBOARDING_LOCKED_UNDER_REVIEW",
                codeOf(() -> service.submitVerificationStep3(userId, bank(true), photo())).getErrorCode());
    }

    @Test
    @DisplayName("Once approved the details are locked too")
    void lockedWhenApproved() {
        givenSubmitted();
        profile.setKycStatus(DeliveryKycStatus.APPROVED);

        assertEquals("ONBOARDING_LOCKED_APPROVED",
                codeOf(() -> service.submitVerificationStep1(userId, "DL-1", photo())).getErrorCode());
        assertEquals("ONBOARDING_LOCKED_APPROVED",
                codeOf(() -> service.submitVerificationStep2(userId, "BIKE", "P", "M", 2020, photo())).getErrorCode());
        verify(cloudinaryService, never()).uploadDocument(any(), anyString());
    }

    // ── withdraw ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Withdrawing returns the application to a draft parked on the final step")
    void withdraw() {
        givenSubmitted();

        OnboardingStatusResponseDto status = service.withdraw(userId);

        assertNull(profile.getApplicationSubmittedAt());
        assertEquals(2, profile.getVerificationStep());
        assertEquals(1, profile.getSubmissionCount(), "a withdrawal does not undo the submission count");
        assertEquals(DeliveryApplicationState.DRAFT, status.getApplicationState());
        assertEquals(OnboardingNextAction.VERIFICATION_STEP_3, status.getNextAction());
        assertTrue(status.isCanEdit());
        ArgumentCaptor<DeliveryKycReviewEntry> entry = ArgumentCaptor.forClass(DeliveryKycReviewEntry.class);
        verify(historyRepository).save(entry.capture());
        assertEquals(DeliveryKycReviewAction.WITHDRAWN, entry.getValue().getAction());
    }

    @Test
    @DisplayName("Only an application that is under review can be withdrawn")
    void withdrawOnlyWhenUnderReview() {
        givenReadyForStep3(); // a draft

        assertEquals("ONBOARDING_NOT_UNDER_REVIEW", codeOf(() -> service.withdraw(userId)).getErrorCode());

        givenSubmitted();
        profile.setKycStatus(DeliveryKycStatus.APPROVED);
        assertEquals("ONBOARDING_NOT_UNDER_REVIEW", codeOf(() -> service.withdraw(userId)).getErrorCode());
    }

    // ── rejected: fix and resubmit ──────────────────────────────────────────

    @Test
    @DisplayName("A rejected rider sees the reason and sections, can edit, and resubmits as submission #2")
    void rejectedThenResubmitted() {
        givenSubmitted();
        profile.setKycStatus(DeliveryKycStatus.REJECTED);
        profile.setRejectionReason("License photo is blurry");
        profile.setRejectedSections("LICENSE,VEHICLE");
        profile.setRejectionCount(1);

        OnboardingStatusResponseDto rejected = service.getStatus(userId);
        assertEquals(OnboardingNextAction.REJECTED, rejected.getNextAction());
        assertEquals("License photo is blurry", rejected.getRejectionReason());
        assertEquals(List.of(DeliveryKycSection.LICENSE, DeliveryKycSection.VEHICLE), rejected.getRejectedSections());
        assertTrue(rejected.isCanEdit());
        assertEquals(1, rejected.getRejectionCount());

        // fixes the license (no new photo needed if one is on file) and resubmits through the final step
        service.submitVerificationStep1(userId, "DL-NEW", photo());
        OnboardingStatusResponseDto resubmitted = service.submitVerificationStep3(userId, bank(true), photo());

        assertEquals(DeliveryKycStatus.PENDING, profile.getKycStatus());
        assertEquals(2, profile.getSubmissionCount());
        assertEquals(1, profile.getRejectionCount(), "the rejection stays counted");
        assertNull(profile.getRejectionReason(), "the old reason moves out of the profile (it stays in the history)");
        assertNull(profile.getRejectedSections());
        assertEquals(DeliveryApplicationState.UNDER_REVIEW, resubmitted.getApplicationState());
        assertNull(resubmitted.getRejectionReason());
        ArgumentCaptor<DeliveryKycReviewEntry> entry = ArgumentCaptor.forClass(DeliveryKycReviewEntry.class);
        verify(historyRepository).save(entry.capture());
        assertEquals(2, entry.getValue().getSubmissionNumber());
    }

    @Test
    @DisplayName("The saved application is returned for prefilling")
    void applicationIsReturnedForPrefill() {
        givenReadyForStep3();
        profile.setFullName("Ravi Kumar");
        profile.setCityOfOperation("Dehradun");
        profile.setLicenseNumber("DL-1");
        profile.setVehicleType("BIKE");

        var application = service.getApplication(userId);

        assertEquals("Ravi Kumar", application.getBasicInfo().getFullName());
        assertEquals("DL-1", application.getLicense().getLicenseNumber());
        assertTrue(application.getLicense().getDocument().isUploaded());
        assertEquals("BIKE", application.getVehicle().getVehicleType());
        assertFalse(application.getBank().getProof().isUploaded());
        assertEquals(OnboardingNextAction.VERIFICATION_STEP_3, application.getStatus().getNextAction());
    }
}
