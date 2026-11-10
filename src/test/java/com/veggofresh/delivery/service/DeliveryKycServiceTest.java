package com.veggofresh.delivery.service;

import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.dto.DeliveryKycDetailDto;
import com.veggofresh.delivery.entity.DeliveryKycReviewAction;
import com.veggofresh.delivery.entity.DeliveryKycReviewEntry;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryDocumentRepository;
import com.veggofresh.delivery.repository.DeliveryKycReviewHistoryRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.platform.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Admin review of rider applications: only a submitted application under review can be approved or rejected, a
 * rejection needs a reason, every decision is recorded in the rider's history, and the rider is told.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryKycServiceTest {

    @Mock
    private DeliveryPartnerProfileRepository partnerRepository;
    @Mock
    private DeliveryDocumentRepository documentRepository;
    @Mock
    private DeliveryKycReviewHistoryRepository historyRepository;
    @Mock
    private UserLookupService userLookupService;
    @Mock
    private NotificationService notificationService;

    private DeliveryKycService service;
    private DeliveryPartnerProfile partner;

    private final UUID adminId = UUID.randomUUID();
    private final UUID riderId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        service = instantiate();
        partner = new DeliveryPartnerProfile();
        partner.setUserId(riderId);
        when(partnerRepository.findByUserId(riderId)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private DeliveryKycService instantiate() throws Exception {
        Constructor<?> constructor = com.veggofresh.delivery.service.impl.DeliveryKycServiceImpl.class
                .getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == DeliveryPartnerProfileRepository.class) {
                args[i] = partnerRepository;
            } else if (types[i] == DeliveryDocumentRepository.class) {
                args[i] = documentRepository;
            } else if (types[i] == DeliveryKycReviewHistoryRepository.class) {
                args[i] = historyRepository;
            } else if (types[i] == UserLookupService.class) {
                args[i] = userLookupService;
            } else if (types[i] == NotificationService.class) {
                args[i] = notificationService;
            }
        }
        constructor.setAccessible(true);
        return (DeliveryKycService) constructor.newInstance(args);
    }

    private void givenUnderReview() {
        partner.setHasBasicInfo(true);
        partner.setVerificationStep(3);
        partner.setApplicationSubmittedAt(Instant.now());
        partner.setSubmissionCount(1);
        partner.setKycStatus(DeliveryKycStatus.PENDING);
    }

    // ── approve ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Approving an application under review approves it, records it and tells the rider")
    void approve() {
        givenUnderReview();
        partner.setRejectionReason("old reason");
        partner.setRejectedSections("LICENSE");

        service.approveKyc(adminId, riderId);

        assertEquals(DeliveryKycStatus.APPROVED, partner.getKycStatus());
        assertNull(partner.getRejectionReason());
        assertNull(partner.getRejectedSections());
        ArgumentCaptor<DeliveryKycReviewEntry> entry = ArgumentCaptor.forClass(DeliveryKycReviewEntry.class);
        verify(historyRepository).save(entry.capture());
        assertEquals(DeliveryKycReviewAction.APPROVED, entry.getValue().getAction());
        assertEquals(adminId, entry.getValue().getAdminUserId());
        verify(notificationService).send(eq(riderId), any(), eq(NotificationType.DELIVERY_KYC_APPROVED),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A rider who never submitted (a draft) cannot be approved")
    void cannotApproveADraft() {
        partner.setHasBasicInfo(true); // only basic info: kycStatus is PENDING but nothing was submitted

        BusinessException ex = assertThrows(BusinessException.class, () -> service.approveKyc(adminId, riderId));

        assertEquals("KYC_NOT_UNDER_REVIEW", ex.getErrorCode());
        assertEquals(DeliveryKycStatus.PENDING, partner.getKycStatus());
        verify(partnerRepository, never()).save(any());
        verify(notificationService, never()).send(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("An application that is already decided cannot be decided again")
    void cannotApproveTwice() {
        givenUnderReview();
        partner.setKycStatus(DeliveryKycStatus.APPROVED);

        assertEquals("KYC_NOT_UNDER_REVIEW",
                assertThrows(BusinessException.class, () -> service.approveKyc(adminId, riderId)).getErrorCode());
        partner.setKycStatus(DeliveryKycStatus.REJECTED);
        assertEquals("KYC_NOT_UNDER_REVIEW",
                assertThrows(BusinessException.class, () -> service.approveKyc(adminId, riderId)).getErrorCode());
    }

    // ── reject ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rejecting records the reason, flagged sections, count and history, and tells the rider")
    void reject() {
        givenUnderReview();

        service.rejectKyc(adminId, riderId, "  License photo is blurry ",
                List.of(DeliveryKycSection.LICENSE, DeliveryKycSection.BANK));

        assertEquals(DeliveryKycStatus.REJECTED, partner.getKycStatus());
        assertEquals("License photo is blurry", partner.getRejectionReason());
        assertEquals("LICENSE,BANK", partner.getRejectedSections());
        assertEquals(1, partner.getRejectionCount());
        ArgumentCaptor<DeliveryKycReviewEntry> entry = ArgumentCaptor.forClass(DeliveryKycReviewEntry.class);
        verify(historyRepository).save(entry.capture());
        assertEquals(DeliveryKycReviewAction.REJECTED, entry.getValue().getAction());
        assertEquals("License photo is blurry", entry.getValue().getReason());
        assertEquals("LICENSE,BANK", entry.getValue().getSections());
        assertEquals(adminId, entry.getValue().getAdminUserId());
        verify(notificationService).send(eq(riderId), any(), eq(NotificationType.DELIVERY_KYC_REJECTED),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A second rejection is counted, so the admin can tell it was rejected more than once")
    void secondRejectionIsCounted() {
        givenUnderReview();
        partner.setRejectionCount(1);
        partner.setSubmissionCount(2);

        service.rejectKyc(adminId, riderId, "Bank proof does not match", null);

        assertEquals(2, partner.getRejectionCount());
        assertNull(partner.getRejectedSections());
    }

    @Test
    @DisplayName("A rejection needs a reason")
    void rejectNeedsAReason() {
        givenUnderReview();

        assertEquals("KYC_REJECTION_REASON_REQUIRED",
                assertThrows(BusinessException.class, () -> service.rejectKyc(adminId, riderId, "   ", null)).getErrorCode());
        assertEquals("KYC_REJECTION_REASON_REQUIRED",
                assertThrows(BusinessException.class, () -> service.rejectKyc(adminId, riderId, null, null)).getErrorCode());
        assertEquals("KYC_REJECTION_REASON_TOO_LONG",
                assertThrows(BusinessException.class,
                        () -> service.rejectKyc(adminId, riderId, "x".repeat(501), null)).getErrorCode());
        assertEquals(DeliveryKycStatus.PENDING, partner.getKycStatus());
        verify(partnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("A rider who never submitted cannot be rejected either")
    void cannotRejectADraft() {
        partner.setHasBasicInfo(true);

        assertEquals("KYC_NOT_UNDER_REVIEW",
                assertThrows(BusinessException.class,
                        () -> service.rejectKyc(adminId, riderId, "No reason needed", null)).getErrorCode());
        verify(historyRepository, never()).save(any());
    }

    // ── queue and detail ────────────────────────────────────────────────────

    @Test
    @DisplayName("The queue lists submitted applications only, oldest first")
    void queueIsSubmittedOnlyOldestFirst() {
        givenUnderReview();
        when(partnerRepository.findByKycStatusAndApplicationSubmittedAtIsNotNull(eq(DeliveryKycStatus.PENDING), any()))
                .thenAnswer(i -> new PageImpl<>(List.of(partner), i.getArgument(1), 1));

        Page<?> page = service.listPendingKyc(PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(partnerRepository).findByKycStatusAndApplicationSubmittedAtIsNotNull(eq(DeliveryKycStatus.PENDING),
                pageable.capture());
        Sort.Order order = pageable.getValue().getSort().getOrderFor("applicationSubmittedAt");
        assertNotNull(order, "the queue must be sorted by submission time");
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    @DisplayName("The detail shows state, counts and the full history, and flags a resubmission")
    void detailShowsHistory() {
        givenUnderReview();
        partner.setSubmissionCount(2);
        partner.setRejectionCount(1);
        DeliveryKycReviewEntry first = DeliveryKycReviewEntry.of(riderId, DeliveryKycReviewAction.SUBMITTED, 1, null, null, null);
        DeliveryKycReviewEntry rejected = DeliveryKycReviewEntry.of(riderId, DeliveryKycReviewAction.REJECTED, 1,
                "Blurry photo", "LICENSE", adminId);
        DeliveryKycReviewEntry second = DeliveryKycReviewEntry.of(riderId, DeliveryKycReviewAction.SUBMITTED, 2, null, null, null);
        when(historyRepository.findByDeliveryPartnerUserIdOrderByCreatedAtAscIdAsc(riderId))
                .thenReturn(List.of(first, rejected, second));

        DeliveryKycDetailDto detail = service.getKycDetail(riderId);

        assertEquals("UNDER_REVIEW", detail.getApplicationState());
        assertEquals(2, detail.getSubmissionCount());
        assertEquals(1, detail.getRejectionCount());
        assertTrue(detail.isResubmission());
        assertEquals(3, detail.getHistory().size());
        assertEquals("REJECTED", detail.getHistory().get(1).getAction());
        assertEquals("Blurry photo", detail.getHistory().get(1).getReason());
        assertEquals(List.of("LICENSE"), detail.getHistory().get(1).getSections());
        assertEquals(3, detail.getMissingDocuments().size(), "nothing uploaded in this test: all 3 document types are missing");
    }
}
