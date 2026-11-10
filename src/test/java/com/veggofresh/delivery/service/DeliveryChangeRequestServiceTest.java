package com.veggofresh.delivery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.dto.AdminDeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.DeliveryChangeOverviewDto;
import com.veggofresh.delivery.dto.DeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.entity.DeliveryChangeRequest;
import com.veggofresh.delivery.entity.DeliveryChangeRequestStatus;
import com.veggofresh.delivery.entity.DeliveryDocument;
import com.veggofresh.delivery.entity.DeliveryDocumentStatus;
import com.veggofresh.delivery.entity.DeliveryDocumentType;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryChangeRequestRepository;
import com.veggofresh.delivery.repository.DeliveryDocumentRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.payment.entity.PayoutRequestStatus;
import com.veggofresh.payment.entity.UserBankAccount;
import com.veggofresh.payment.repository.PayoutRequestRepository;
import com.veggofresh.payment.repository.UserBankAccountRepository;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reviewed changes after approval: the rider keeps the old verified details while a change waits for an admin,
 * a change only reaches the profile on approval, and a bank change cannot redirect payouts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryChangeRequestServiceTest {

    @Mock
    private DeliveryPartnerProfileRepository profileRepository;
    @Mock
    private DeliveryDocumentRepository documentRepository;
    @Mock
    private DeliveryChangeRequestRepository changeRequestRepository;
    @Mock
    private UserBankAccountRepository userBankAccountRepository;
    @Mock
    private PayoutRequestRepository payoutRequestRepository;
    @Mock
    private CloudinaryService cloudinaryService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserLookupService userLookupService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeliveryChangeRequestService service;
    private DeliveryPartnerProfile profile;
    private final List<DeliveryDocument> liveDocuments = new ArrayList<>();

    private final UUID riderId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        service = instantiate();
        profile = approvedProfile();
        when(profileRepository.findByUserId(riderId)).thenAnswer(i -> Optional.ofNullable(profile));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(changeRequestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(changeRequestRepository.saveAndFlush(any())).thenAnswer(i -> {
            DeliveryChangeRequest r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        when(changeRequestRepository.findByDeliveryPartnerUserIdAndSectionAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(changeRequestRepository.findByDeliveryPartnerUserIdOrderByCreatedAtDescIdDesc(riderId)).thenReturn(List.of());
        when(documentRepository.findByDeliveryPartnerUserIdAndDocumentType(any(), any())).thenReturn(Optional.empty());
        when(documentRepository.findByDeliveryPartnerUserId(riderId)).thenReturn(liveDocuments);
        when(cloudinaryService.uploadDocument(any(), anyString()))
                .thenReturn(new CloudinaryUploadResult("https://files/new.jpg", "new-public-id"));
        when(payoutRequestRepository.countByUserIdAndStatus(riderId, PayoutRequestStatus.PENDING)).thenReturn(0L);
        when(userBankAccountRepository.findByUserId(riderId)).thenReturn(Optional.empty());
    }

    private DeliveryChangeRequestService instantiate() throws Exception {
        Constructor<?> constructor = com.veggofresh.delivery.service.impl.DeliveryChangeRequestServiceImpl.class
                .getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == DeliveryPartnerProfileRepository.class) {
                args[i] = profileRepository;
            } else if (types[i] == DeliveryDocumentRepository.class) {
                args[i] = documentRepository;
            } else if (types[i] == DeliveryChangeRequestRepository.class) {
                args[i] = changeRequestRepository;
            } else if (types[i] == UserBankAccountRepository.class) {
                args[i] = userBankAccountRepository;
            } else if (types[i] == PayoutRequestRepository.class) {
                args[i] = payoutRequestRepository;
            } else if (types[i] == CloudinaryService.class) {
                args[i] = cloudinaryService;
            } else if (types[i] == NotificationService.class) {
                args[i] = notificationService;
            } else if (types[i] == UserLookupService.class) {
                args[i] = userLookupService;
            } else if (types[i] == ObjectMapper.class) {
                args[i] = objectMapper;
            }
        }
        constructor.setAccessible(true);
        return (DeliveryChangeRequestService) constructor.newInstance(args);
    }

    private DeliveryPartnerProfile approvedProfile() {
        DeliveryPartnerProfile p = new DeliveryPartnerProfile();
        p.setUserId(riderId);
        p.setHasBasicInfo(true);
        p.setVerificationStep(3);
        p.setApplicationSubmittedAt(Instant.now());
        p.setSubmissionCount(1);
        p.setKycStatus(DeliveryKycStatus.APPROVED);
        p.setFullName("Ravi Kumar");
        p.setCityOfOperation("Dehradun");
        p.setLicenseNumber("DL-OLD");
        p.setVehicleType("BIKE");
        p.setPlateNumber("UK07AB1234");
        p.setVehicleModel("Splendor");
        p.setManufactureYear(2020);
        p.setBankName("HDFC");
        p.setAccountHolderName("Ravi Kumar");
        p.setAccountNumber("1234567890");
        p.setIfscCode("HDFC0001234");
        p.setAgreedToPayoutTerms(true);
        return p;
    }

    private static MultipartFile photo() {
        return new MockMultipartFile("photo", "doc.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private static VerificationStep3RequestDto newBank() {
        VerificationStep3RequestDto b = new VerificationStep3RequestDto();
        b.setBankName("SBI");
        b.setAccountHolderName("Ravi Kumar");
        b.setAccountNumber("9876543210");
        b.setIfscCode("SBIN0009999");
        b.setAgreedToPayoutTerms(true);
        return b;
    }

    private String json(Map<String, String> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    /** A request that is waiting for an admin. */
    private DeliveryChangeRequest pending(DeliveryKycSection section, Map<String, String> requested,
                                          String documentUrl, String documentPublicId) throws Exception {
        DeliveryChangeRequest r = new DeliveryChangeRequest();
        r.setId(UUID.randomUUID());
        r.setDeliveryPartnerUserId(riderId);
        r.setSection(section);
        r.setStatus(DeliveryChangeRequestStatus.PENDING);
        r.setPreviousValues("{}");
        r.setRequestedValues(json(requested));
        r.setDocumentUrl(documentUrl);
        r.setDocumentPublicId(documentPublicId);
        when(changeRequestRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private void givenLiveDocument(DeliveryDocumentType type, String url, String publicId) {
        DeliveryDocument doc = new DeliveryDocument();
        doc.setDocumentType(type);
        doc.setFileUrl(url);
        doc.setPublicId(publicId);
        doc.setStatus(DeliveryDocumentStatus.PENDING);
        when(documentRepository.findByDeliveryPartnerUserIdAndDocumentType(riderId, type)).thenReturn(Optional.of(doc));
        liveDocuments.add(doc); // the overview reads the list, the other paths read by type
    }

    private static BusinessException error(Runnable action) {
        return assertThrows(BusinessException.class, action::run);
    }

    // ── submitting ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A rider who is not approved edits the application directly, not through a change request")
    void notApprovedCannotRequest() {
        profile.setKycStatus(DeliveryKycStatus.PENDING);
        profile.setApplicationSubmittedAt(null); // a draft
        BasicInfoRequestDto request = new BasicInfoRequestDto();
        request.setFullName("New Name");
        request.setCityOfOperation("Delhi");

        assertEquals("CHANGE_REQUEST_NOT_AVAILABLE", error(() -> service.submitBasicInfo(riderId, request)).getErrorCode());

        profile.setApplicationSubmittedAt(Instant.now()); // now under review
        assertEquals("CHANGE_REQUEST_NOT_AVAILABLE", error(() -> service.submitBasicInfo(riderId, request)).getErrorCode());
        verify(changeRequestRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("A change is held as a pending request and the live details are NOT touched")
    void changeIsHeldNotApplied() throws Exception {
        BasicInfoRequestDto request = new BasicInfoRequestDto();
        request.setFullName("  Ravi K Sharma ");
        request.setCityOfOperation("Dehradun");

        DeliveryChangeRequestDto created = service.submitBasicInfo(riderId, request);

        assertEquals(DeliveryChangeRequestStatus.PENDING, created.getStatus());
        assertEquals(DeliveryKycSection.BASIC_INFO, created.getSection());
        assertEquals("Ravi K Sharma", created.getRequested().get("fullName"));
        assertEquals("Ravi Kumar", profile.getFullName(), "the rider keeps the verified name meanwhile");
        verify(profileRepository, never()).save(any());

        ArgumentCaptor<DeliveryChangeRequest> saved = ArgumentCaptor.forClass(DeliveryChangeRequest.class);
        verify(changeRequestRepository).saveAndFlush(saved.capture());
        Map<String, String> previous = objectMapper.readValue(saved.getValue().getPreviousValues(),
                new TypeReference<Map<String, String>>() { });
        assertEquals("Ravi Kumar", previous.get("fullName"), "the old value is snapshotted for the admin");
    }

    @Test
    @DisplayName("Sending the same name and city again is refused")
    void sameValuesAreRefused() {
        BasicInfoRequestDto request = new BasicInfoRequestDto();
        request.setFullName("Ravi Kumar");
        request.setCityOfOperation("Dehradun");

        assertEquals("CHANGE_REQUEST_NO_CHANGE", error(() -> service.submitBasicInfo(riderId, request)).getErrorCode());
    }

    @Test
    @DisplayName("A license change needs a new photo as evidence; with one it is held, not applied")
    void licenseNeedsAPhoto() {
        assertEquals("CHANGE_REQUEST_DOCUMENT_REQUIRED",
                error(() -> service.submitLicense(riderId, "DL-NEW", null)).getErrorCode());
        verify(cloudinaryService, never()).uploadDocument(any(), anyString());

        DeliveryChangeRequestDto created = service.submitLicense(riderId, "DL-NEW", photo());

        assertEquals("https://files/new.jpg", created.getDocumentUrl());
        assertEquals("DL-OLD", profile.getLicenseNumber());
        verify(profileRepository, never()).save(any());
    }

    @Test
    @DisplayName("Vehicle and bank changes also need their document; the bank one needs the payout terms")
    void vehicleAndBankNeedEvidence() {
        assertEquals("CHANGE_REQUEST_DOCUMENT_REQUIRED",
                error(() -> service.submitVehicle(riderId, "SCOOTER", "UK07ZZ9999", "Activa", 2022, null)).getErrorCode());
        assertEquals("CHANGE_REQUEST_DOCUMENT_REQUIRED",
                error(() -> service.submitBank(riderId, newBank(), null)).getErrorCode());

        VerificationStep3RequestDto noTerms = newBank();
        noTerms.setAgreedToPayoutTerms(false);
        assertEquals("CHANGE_REQUEST_TERMS_REQUIRED",
                error(() -> service.submitBank(riderId, noTerms, photo())).getErrorCode());

        assertEquals("CHANGE_REQUEST_FIELD_REQUIRED",
                error(() -> service.submitVehicle(riderId, " ", "UK07ZZ9999", "Activa", 2022, photo())).getErrorCode());
    }

    @Test
    @DisplayName("Only one open request per section, but other sections are independent")
    void oneOpenRequestPerSection() throws Exception {
        DeliveryChangeRequest open = pending(DeliveryKycSection.LICENSE, map("licenseNumber", "DL-X"), "u", "p");
        when(changeRequestRepository.findByDeliveryPartnerUserIdAndSectionAndStatus(
                riderId, DeliveryKycSection.LICENSE, DeliveryChangeRequestStatus.PENDING)).thenReturn(Optional.of(open));

        assertEquals("CHANGE_REQUEST_ALREADY_OPEN",
                error(() -> service.submitLicense(riderId, "DL-NEW", photo())).getErrorCode());
        verify(cloudinaryService, never()).uploadDocument(any(), anyString());

        DeliveryChangeRequestDto bank = service.submitBank(riderId, newBank(), photo());
        assertEquals(DeliveryKycSection.BANK, bank.getSection());
    }

    @Test
    @DisplayName("The rider sees their own account number masked")
    void riderSeesMaskedAccountNumber() {
        DeliveryChangeRequestDto created = service.submitBank(riderId, newBank(), photo());

        assertEquals("****3210", created.getRequested().get("accountNumber"));
    }

    // ── cancel ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cancelling an open request marks it cancelled and removes the held file")
    void cancel() throws Exception {
        DeliveryChangeRequest open = pending(DeliveryKycSection.LICENSE, map("licenseNumber", "DL-X"), "https://f/x.jpg", "pid-x");

        DeliveryChangeRequestDto result = service.cancel(riderId, open.getId());

        assertEquals(DeliveryChangeRequestStatus.CANCELLED, result.getStatus());
        assertNull(open.getDocumentUrl());
        verify(cloudinaryService).deleteQuietly("pid-x");
    }

    @Test
    @DisplayName("A rider cannot cancel someone else's request or one that is already decided")
    void cancelGuards() throws Exception {
        DeliveryChangeRequest open = pending(DeliveryKycSection.LICENSE, map("licenseNumber", "DL-X"), null, null);

        assertEquals("CHANGE_REQUEST_NOT_FOUND",
                error(() -> service.cancel(UUID.randomUUID(), open.getId())).getErrorCode());

        open.setStatus(DeliveryChangeRequestStatus.APPROVED);
        assertEquals("CHANGE_REQUEST_NOT_PENDING", error(() -> service.cancel(riderId, open.getId())).getErrorCode());
    }

    // ── approve ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Approving a license change applies the number, replaces the document and tells the rider")
    void approveLicense() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.LICENSE, map("licenseNumber", "DL-NEW"),
                "https://files/new.jpg", "new-pid");
        givenLiveDocument(DeliveryDocumentType.LICENSE, "https://files/old.jpg", "old-pid");

        service.approve(adminId, request.getId());

        assertEquals("DL-NEW", profile.getLicenseNumber());
        assertEquals(DeliveryChangeRequestStatus.APPROVED, request.getStatus());
        assertEquals(adminId, request.getDecidedBy());
        assertNotNull(request.getDecidedAt());
        ArgumentCaptor<DeliveryDocument> doc = ArgumentCaptor.forClass(DeliveryDocument.class);
        verify(documentRepository).save(doc.capture());
        assertEquals("https://files/new.jpg", doc.getValue().getFileUrl());
        assertEquals("new-pid", doc.getValue().getPublicId());
        assertEquals(DeliveryDocumentStatus.VERIFIED, doc.getValue().getStatus());
        verify(cloudinaryService).deleteQuietly("old-pid");
        verify(notificationService).send(eq(riderId), any(), eq(NotificationType.DELIVERY_CHANGE_APPROVED),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Approving a vehicle change applies all four vehicle details")
    void approveVehicle() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.VEHICLE,
                map("vehicleType", "SCOOTER", "plateNumber", "UK07ZZ9999", "vehicleModel", "Activa", "manufactureYear", "2022"),
                "https://files/ins.jpg", "ins-pid");

        service.approve(adminId, request.getId());

        assertEquals("SCOOTER", profile.getVehicleType());
        assertEquals("UK07ZZ9999", profile.getPlateNumber());
        assertEquals("Activa", profile.getVehicleModel());
        assertEquals(2022, profile.getManufactureYear());
    }

    @Test
    @DisplayName("Approving a name and city change applies it")
    void approveBasicInfo() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.BASIC_INFO,
                map("fullName", "Ravi K Sharma", "cityOfOperation", "Haridwar"), null, null);

        service.approve(adminId, request.getId());

        assertEquals("Ravi K Sharma", profile.getFullName());
        assertEquals("Haridwar", profile.getCityOfOperation());
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("A bank change is refused while the rider has payouts waiting for approval")
    void bankChangeBlockedByPendingPayouts() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.BANK,
                map("bankName", "SBI", "accountHolderName", "Ravi Kumar", "accountNumber", "9876543210", "ifscCode", "SBIN0009999"),
                "https://files/proof.jpg", "proof-pid");
        when(payoutRequestRepository.countByUserIdAndStatus(riderId, PayoutRequestStatus.PENDING)).thenReturn(1L);

        BusinessException ex = error(() -> service.approve(adminId, request.getId()));

        assertEquals("CHANGE_BLOCKED_PENDING_PAYOUTS", ex.getErrorCode());
        assertEquals("1234567890", profile.getAccountNumber(), "nothing was applied");
        assertEquals(DeliveryChangeRequestStatus.PENDING, request.getStatus());
        verify(profileRepository, never()).save(any());
        verify(notificationService, never()).send(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("An approved bank change goes live and resets the cached Razorpay fund account")
    void approveBankResetsTheFundAccount() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.BANK,
                map("bankName", "SBI", "accountHolderName", "Ravi Kumar", "accountNumber", "9876543210", "ifscCode", "SBIN0009999"),
                "https://files/proof.jpg", "proof-pid");
        UserBankAccount account = new UserBankAccount();
        account.setUserId(riderId);
        account.setAccountNumber("1234567890");
        account.setIfscCode("HDFC0001234");
        account.setAccountHolderName("Ravi Kumar");
        account.setRazorpayFundAccountId("fa_old");
        when(userBankAccountRepository.findByUserId(riderId)).thenReturn(Optional.of(account));

        service.approve(adminId, request.getId());

        assertEquals("9876543210", profile.getAccountNumber());
        assertEquals("SBIN0009999", profile.getIfscCode());
        assertTrue(profile.isAgreedToPayoutTerms());
        assertEquals("9876543210", account.getAccountNumber(), "the payout account follows right away");
        assertNull(account.getRazorpayFundAccountId(), "payouts must not keep going to the old bank");
        verify(userBankAccountRepository).save(account);
    }

    @Test
    @DisplayName("A request that is not pending cannot be approved again")
    void cannotApproveTwice() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.BASIC_INFO, map("fullName", "A", "cityOfOperation", "B"), null, null);
        request.setStatus(DeliveryChangeRequestStatus.REJECTED);

        assertEquals("CHANGE_REQUEST_NOT_PENDING", error(() -> service.approve(adminId, request.getId())).getErrorCode());
    }

    @Test
    @DisplayName("A rider who is no longer approved cannot have a change applied")
    void partnerMustStillBeApproved() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.BASIC_INFO, map("fullName", "A", "cityOfOperation", "B"), null, null);
        profile.setKycStatus(DeliveryKycStatus.REJECTED);

        assertEquals("CHANGE_REQUEST_PARTNER_NOT_APPROVED", error(() -> service.approve(adminId, request.getId())).getErrorCode());
    }

    // ── reject ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rejecting needs a reason, keeps the live details and tells the rider")
    void reject() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.LICENSE, map("licenseNumber", "DL-NEW"),
                "https://files/new.jpg", "new-pid");

        assertEquals("CHANGE_REJECTION_REASON_REQUIRED", error(() -> service.reject(adminId, request.getId(), "  ")).getErrorCode());
        assertEquals("CHANGE_REJECTION_REASON_TOO_LONG",
                error(() -> service.reject(adminId, request.getId(), "x".repeat(501))).getErrorCode());
        assertEquals(DeliveryChangeRequestStatus.PENDING, request.getStatus());

        service.reject(adminId, request.getId(), " Photo is blurry ");

        assertEquals(DeliveryChangeRequestStatus.REJECTED, request.getStatus());
        assertEquals("Photo is blurry", request.getDecisionReason());
        assertEquals("DL-OLD", profile.getLicenseNumber());
        verify(profileRepository, never()).save(any());
        verify(cloudinaryService, never()).deleteQuietly(anyString());
        verify(notificationService).send(eq(riderId), any(), eq(NotificationType.DELIVERY_CHANGE_REJECTED),
                anyString(), anyString(), anyString());
    }

    // ── what the rider sees ─────────────────────────────────────────────────

    @Test
    @DisplayName("The overview shows each section as verified, under review or rejected")
    void overview() throws Exception {
        DeliveryChangeRequest license = pending(DeliveryKycSection.LICENSE, map("licenseNumber", "DL-NEW"), "https://f/l.jpg", "p");
        DeliveryChangeRequest bank = pending(DeliveryKycSection.BANK, map("accountNumber", "9876543210"), null, null);
        bank.setStatus(DeliveryChangeRequestStatus.REJECTED);
        bank.setDecisionReason("Proof does not match");
        bank.setDecidedAt(Instant.now());
        when(changeRequestRepository.findByDeliveryPartnerUserIdOrderByCreatedAtDescIdDesc(riderId))
                .thenReturn(List.of(license, bank));
        givenLiveDocument(DeliveryDocumentType.LICENSE, "https://files/live-license.jpg", "pid");

        DeliveryChangeOverviewDto overview = service.getOverview(riderId);

        assertTrue(overview.isCanRequestChanges());
        assertEquals(4, overview.getSections().size());
        Map<DeliveryKycSection, DeliveryChangeOverviewDto.SectionOverview> by = new java.util.EnumMap<>(DeliveryKycSection.class);
        overview.getSections().forEach(s -> by.put(s.getSection(), s));

        assertEquals("VERIFIED", by.get(DeliveryKycSection.BASIC_INFO).getState());
        assertEquals("VERIFIED", by.get(DeliveryKycSection.VEHICLE).getState());

        DeliveryChangeOverviewDto.SectionOverview lic = by.get(DeliveryKycSection.LICENSE);
        assertEquals("UNDER_REVIEW", lic.getState());
        assertEquals("DL-OLD", lic.getCurrent().get("licenseNumber"), "the live value is still the old one");
        assertEquals("DL-NEW", lic.getPendingRequest().getRequested().get("licenseNumber"));
        assertEquals("https://files/live-license.jpg", lic.getDocumentUrl());

        DeliveryChangeOverviewDto.SectionOverview bankSection = by.get(DeliveryKycSection.BANK);
        assertEquals("REJECTED", bankSection.getState());
        assertEquals("Proof does not match", bankSection.getLastRejection().getReason());
        assertEquals("****7890", bankSection.getCurrent().get("accountNumber"));
        assertNull(bankSection.getPendingRequest());
    }

    @Test
    @DisplayName("A rider who is not approved cannot request changes")
    void overviewForADraft() {
        profile.setKycStatus(DeliveryKycStatus.PENDING);
        profile.setApplicationSubmittedAt(null);

        assertFalse(service.getOverview(riderId).isCanRequestChanges());
    }

    // ── admin ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("With no filters the admin list is the pending queue, oldest first")
    void adminQueueIsPendingOldestFirst() {
        when(changeRequestRepository.findWithFilters(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, null, PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(changeRequestRepository).findWithFilters(eq(DeliveryChangeRequestStatus.PENDING), eq(null), pageable.capture());
        Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
        assertNotNull(order);
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    @DisplayName("For one rider and no status the admin sees the full history, newest first")
    void adminHistoryForOneRider() {
        when(changeRequestRepository.findForPartnerWithFilters(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, riderId, PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(changeRequestRepository).findForPartnerWithFilters(eq(riderId), eq(null), eq(null), pageable.capture());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("createdAt").getDirection());
    }

    @Test
    @DisplayName("The admin detail shows old vs new, the documents and the rider's waiting payouts")
    void adminDetailShowsOldAndNew() throws Exception {
        DeliveryChangeRequest request = pending(DeliveryKycSection.BANK,
                map("bankName", "SBI", "accountHolderName", "Ravi Kumar", "accountNumber", "9876543210", "ifscCode", "SBIN0009999"),
                "https://files/proof.jpg", "proof-pid");
        request.setPreviousValues(json(map("accountNumber", "1234567890")));
        givenLiveDocument(DeliveryDocumentType.BANK_INFO, "https://files/old-proof.jpg", "old");
        when(payoutRequestRepository.countByUserIdAndStatus(riderId, PayoutRequestStatus.PENDING)).thenReturn(2L);

        AdminDeliveryChangeRequestDto detail = service.getDetail(request.getId());

        assertEquals("Ravi Kumar", detail.getFullName());
        assertEquals("1234567890", detail.getPrevious().get("accountNumber"));
        assertEquals("1234567890", detail.getCurrent().get("accountNumber"), "the admin sees the full number");
        assertEquals("9876543210", detail.getRequested().get("accountNumber"));
        assertEquals("https://files/proof.jpg", detail.getDocumentUrl());
        assertEquals("https://files/old-proof.jpg", detail.getCurrentDocumentUrl());
        assertEquals(2, detail.getPendingPayoutRequests());
    }
}
