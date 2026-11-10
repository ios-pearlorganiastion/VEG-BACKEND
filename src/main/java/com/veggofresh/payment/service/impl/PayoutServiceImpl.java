package com.veggofresh.payment.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.payment.client.RazorpayXClient;
import com.veggofresh.payment.config.RazorpayProperties;
import com.veggofresh.payment.dto.AdminPayoutActionDto;
import com.veggofresh.payment.dto.PayoutRequestCreateDto;
import com.veggofresh.payment.dto.PayoutResponseDto;
import com.veggofresh.payment.dto.UserBankAccountDto;
import com.veggofresh.payment.dto.WalletBalanceDto;
import com.veggofresh.payment.entity.PayoutRequest;
import com.veggofresh.payment.entity.PayoutRequestStatus;
import com.veggofresh.payment.entity.UserBankAccount;
import com.veggofresh.payment.repository.PayoutRequestRepository;
import com.veggofresh.payment.repository.UserBankAccountRepository;
import com.veggofresh.payment.service.PayoutService;
import com.veggofresh.payment.service.WalletService;
import com.veggofresh.payment.service.WalletTransactionReason;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.entity.KycStatus;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PayoutServiceImpl implements PayoutService {

    private final PayoutRequestRepository payoutRequestRepository;
    private final UserBankAccountRepository userBankAccountRepository;
    private final WalletService walletService;
    private final RazorpayXClient razorpayXClient;
    private final RazorpayProperties razorpayProperties;
    private final UserLookupService userLookupService;

    // Option B: direct cross-module repository injection
    private final DeliveryPartnerProfileRepository deliveryPartnerProfileRepository;
    private final ShopRepository shopRepository;

    @Override
    public PayoutResponseDto requestPayout(UUID userId, String userRole, PayoutRequestCreateDto dto) {
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ONE) < 0) {
            throw new BusinessException("INVALID_PAYOUT_AMOUNT", "Payout amount must be at least 1.00",
                    HttpStatus.BAD_REQUEST);
        }

        // ── Eligibility: KYC must be APPROVED ─────────────────────────────
        // Bank details are collected during onboarding for both roles.
        // No separate admin bank-verification gate exists anymore.
        UserBankAccount bankAccount;
        if ("DELIVERY".equalsIgnoreCase(userRole)) {
            bankAccount = resolveAndSyncDeliveryBankAccount(userId);
        } else {
            // VENDOR
            bankAccount = resolveAndSyncVendorBankAccount(userId);
        }

        // ── Wallet balance check ───────────────────────────────────────────
        WalletBalanceDto wallet = walletService.getBalance(userId);
        if (wallet.getBalance().compareTo(dto.getAmount()) < 0) {
            throw new BusinessException("INSUFFICIENT_WALLET_BALANCE", "Insufficient wallet balance for withdrawal",
                    HttpStatus.BAD_REQUEST);
        }

        // ── Create Payout Request ──────────────────────────────────────────
        PayoutRequest request = new PayoutRequest();
        request.setUserId(userId);
        request.setUserRole(userRole != null ? userRole.toUpperCase() : "VENDOR");
        request.setAmount(dto.getAmount());
        request.setBankAccountId(bankAccount.getId());
        request.setStatus(PayoutRequestStatus.PENDING);
        payoutRequestRepository.save(request);

        // Immediately debit wallet balance so money cannot be double spent
        walletService.debit(
                userId,
                dto.getAmount(),
                WalletTransactionReason.PAYOUT_REQUEST_DEBIT,
                request.getId(),
                "Payout withdrawal request #" + request.getId());

        return mapToDto(request, bankAccount);
    }

    /**
     * Checks that delivery KYC is APPROVED, then auto-syncs bank details from
     * DeliveryPartnerProfile into the UserBankAccount table (creating/updating as
     * needed).
     * This means delivery partners never have to re-enter bank details separately.
     */
    private UserBankAccount resolveAndSyncDeliveryBankAccount(UUID userId) {
        DeliveryPartnerProfile profile = deliveryPartnerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND",
                        "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        if (profile.getKycStatus() != DeliveryKycStatus.APPROVED) {
            throw new BusinessException("KYC_NOT_APPROVED",
                    "Your KYC application must be approved before you can request a withdrawal. Current status: "
                            + profile.getKycStatus(),
                    HttpStatus.FORBIDDEN);
        }

        if (profile.getAccountNumber() == null || profile.getAccountNumber().isBlank()) {
            throw new BusinessException("BANK_ACCOUNT_REQUIRED",
                    "Bank details are missing from your onboarding profile. Please contact support.",
                    HttpStatus.BAD_REQUEST);
        }

        // Auto-sync: upsert UserBankAccount from onboarding data
        UserBankAccount account = userBankAccountRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserBankAccount a = new UserBankAccount();
                    a.setUserId(userId);
                    return a;
                });

        // The Razorpay fund account is created once from these details and cached on the row. If the details have
        // changed, the cached fund account still points at the OLD bank account, so payouts would silently keep
        // going there. Drop it so the next payout creates a fresh one for the new details.
        boolean detailsChanged = !Objects.equals(account.getAccountNumber(), profile.getAccountNumber())
                || !Objects.equals(account.getIfscCode(), profile.getIfscCode())
                || !Objects.equals(account.getAccountHolderName(), profile.getAccountHolderName());

        account.setAccountHolderName(profile.getAccountHolderName());
        account.setAccountNumber(profile.getAccountNumber());
        account.setIfscCode(profile.getIfscCode());
        account.setBankName(profile.getBankName());
        if (detailsChanged) {
            account.setRazorpayFundAccountId(null);
        }
        return userBankAccountRepository.save(account);
    }

    /**
     * Checks that vendor KYC is APPROVED, then auto-syncs bank details from the
     * Shop entity into the UserBankAccount table.
     */
    private UserBankAccount resolveAndSyncVendorBankAccount(UUID userId) {
        Shop shop = shopRepository.findByOwnerUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException("VENDOR_SHOP_NOT_FOUND",
                        "Vendor shop not found", HttpStatus.NOT_FOUND));

        if (shop.getKycStatus() != KycStatus.APPROVED) {
            throw new BusinessException("KYC_NOT_APPROVED",
                    "Your KYC application must be approved before you can request a withdrawal. Current status: "
                            + shop.getKycStatus(),
                    HttpStatus.FORBIDDEN);
        }

        if (shop.getAccountNumber() == null || shop.getAccountNumber().isBlank()) {
            throw new BusinessException("BANK_ACCOUNT_REQUIRED",
                    "Bank details are missing from your onboarding profile. Please complete Step 3 (bank details) of onboarding.",
                    HttpStatus.BAD_REQUEST);
        }

        // Auto-sync: upsert UserBankAccount from onboarding data
        UserBankAccount account = userBankAccountRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserBankAccount a = new UserBankAccount();
                    a.setUserId(userId);
                    return a;
                });

        account.setAccountHolderName(shop.getAccountHolderName());
        account.setAccountNumber(shop.getAccountNumber());
        account.setIfscCode(shop.getIfscCode());
        account.setBankName(shop.getBankName());
        return userBankAccountRepository.save(account);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayoutResponseDto> getMyPayoutRequests(UUID userId, Pageable pageable) {
        UserBankAccount bankAccount = userBankAccountRepository.findByUserId(userId).orElse(null);
        Page<PayoutRequest> page = payoutRequestRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<PayoutResponseDto> dtos = page.getContent().stream()
                .map(r -> mapToDto(r, bankAccount))
                .collect(Collectors.toList());
        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayoutResponseDto> getAdminPayoutRequests(PayoutRequestStatus status, Pageable pageable) {
        Page<PayoutRequest> page;
        if (status != null) {
            page = payoutRequestRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            page = payoutRequestRepository.findAll(pageable);
        }

        List<PayoutResponseDto> dtos = page.getContent().stream()
                .map(r -> {
                    UserBankAccount bankAccount = r.getBankAccountId() != null
                            ? userBankAccountRepository.findById(r.getBankAccountId()).orElse(null)
                            : userBankAccountRepository.findByUserId(r.getUserId()).orElse(null);
                    return mapToDto(r, bankAccount);
                })
                .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    @Override
    public PayoutResponseDto approvePayout(UUID payoutRequestId, UUID adminUserId, AdminPayoutActionDto dto) {
        PayoutRequest request = payoutRequestRepository.findById(payoutRequestId)
                .orElseThrow(() -> new BusinessException("PAYOUT_REQUEST_NOT_FOUND", "Payout request not found",
                        HttpStatus.NOT_FOUND));

        if (request.getStatus() != PayoutRequestStatus.PENDING) {
            throw new BusinessException("INVALID_PAYOUT_STATUS", "Only PENDING payout requests can be approved",
                    HttpStatus.BAD_REQUEST);
        }

        UserBankAccount bankAccount = request.getBankAccountId() != null
                ? userBankAccountRepository.findById(request.getBankAccountId()).orElse(null)
                : userBankAccountRepository.findByUserId(request.getUserId()).orElse(null);

        if (bankAccount == null) {
            throw new BusinessException("BANK_ACCOUNT_NOT_FOUND", "No bank account found for this payout request",
                    HttpStatus.NOT_FOUND);
        }

        request.setStatus(PayoutRequestStatus.PROCESSING);
        if (dto != null && dto.getNotes() != null) {
            request.setAdminNotes(dto.getNotes());
        }

        // Resolve user contact info for RazorpayX Contact creation
        UserSummaryDto userSummary = userLookupService.findById(request.getUserId()).orElse(null);
        String name = bankAccount.getAccountHolderName();
        String email = userSummary != null ? userSummary.getEmail()
                : "user_" + request.getUserId().toString().substring(0, 8) + "@veggofresh.com";
        String phone = userSummary != null ? userSummary.getPhone() : "9999999999";

        // 1. Contact Creation
        if (bankAccount.getRazorpayContactId() == null) {
            String contactId = razorpayXClient.createContact(name, email, phone, request.getUserRole(),
                    request.getUserId().toString());
            bankAccount.setRazorpayContactId(contactId);
            userBankAccountRepository.save(bankAccount);
        }

        // 2. Fund Account Creation
        if (bankAccount.getRazorpayFundAccountId() == null) {
            String fundAccountId = razorpayXClient.createFundAccount(
                    bankAccount.getRazorpayContactId(),
                    bankAccount.getAccountHolderName(),
                    bankAccount.getAccountNumber(),
                    bankAccount.getIfscCode());
            bankAccount.setRazorpayFundAccountId(fundAccountId);
            userBankAccountRepository.save(bankAccount);
        }

        // 3. Trigger RazorpayX Payout
        String payoutId = razorpayXClient.createPayout(
                razorpayProperties.getAccountNumber(),
                bankAccount.getRazorpayFundAccountId(),
                request.getAmount(),
                "INR",
                "NEFT",
                "payout",
                request.getId().toString());

        request.setRazorpayPayoutId(payoutId);
        request.setStatus(PayoutRequestStatus.APPROVED);
        request.setProcessedAt(Instant.now());
        payoutRequestRepository.save(request);

        log.info("Admin {} approved payout request {} -- Razorpay Payout ID: {}", adminUserId, payoutRequestId,
                payoutId);
        return mapToDto(request, bankAccount);
    }

    @Override
    public PayoutResponseDto rejectPayout(UUID payoutRequestId, UUID adminUserId, AdminPayoutActionDto dto) {
        PayoutRequest request = payoutRequestRepository.findById(payoutRequestId)
                .orElseThrow(() -> new BusinessException("PAYOUT_REQUEST_NOT_FOUND", "Payout request not found",
                        HttpStatus.NOT_FOUND));

        if (request.getStatus() != PayoutRequestStatus.PENDING) {
            throw new BusinessException("INVALID_PAYOUT_STATUS", "Only PENDING payout requests can be rejected",
                    HttpStatus.BAD_REQUEST);
        }

        request.setStatus(PayoutRequestStatus.REJECTED);
        request.setProcessedAt(Instant.now());
        if (dto != null) {
            request.setAdminNotes(dto.getNotes());
            request.setFailureReason(dto.getRejectionReason() != null ? dto.getRejectionReason() : "Rejected by Admin");
        } else {
            request.setFailureReason("Rejected by Admin");
        }
        payoutRequestRepository.save(request);

        // Refund money back to user's wallet
        walletService.credit(
                request.getUserId(),
                request.getAmount(),
                WalletTransactionReason.PAYOUT_REJECTED_REFUND,
                request.getId(),
                "Refund for rejected withdrawal request #" + request.getId());

        UserBankAccount bankAccount = request.getBankAccountId() != null
                ? userBankAccountRepository.findById(request.getBankAccountId()).orElse(null)
                : userBankAccountRepository.findByUserId(request.getUserId()).orElse(null);

        log.info("Admin {} rejected payout request {} -- Wallet refunded", adminUserId, payoutRequestId);
        return mapToDto(request, bankAccount);
    }

    @Override
    public void handleRazorpayXWebhook(String eventType, Map<String, Object> payload) {
        log.info("Received RazorpayX Webhook event: {}", eventType);
        if (payload == null || !payload.containsKey("payload"))
            return;

        Map payloadData = (Map) payload.get("payload");
        if (payloadData == null || !payloadData.containsKey("payout"))
            return;

        Map payoutData = (Map) ((Map) payloadData.get("payout")).get("entity");
        if (payoutData == null)
            return;

        String payoutId = (String) payoutData.get("id");
        String referenceId = (String) payoutData.get("reference_id");

        PayoutRequest request = null;
        if (payoutId != null) {
            request = payoutRequestRepository.findByRazorpayPayoutId(payoutId).orElse(null);
        }
        if (request == null && referenceId != null) {
            try {
                request = payoutRequestRepository.findById(UUID.fromString(referenceId)).orElse(null);
            } catch (Exception ignored) {
            }
        }

        if (request == null) {
            log.warn("RazorpayX webhook: Payout request not found for payoutId={}, referenceId={}", payoutId,
                    referenceId);
            return;
        }

        if ("payout.processed".equalsIgnoreCase(eventType)) {
            request.setStatus(PayoutRequestStatus.COMPLETED);
            request.setProcessedAt(Instant.now());
            payoutRequestRepository.save(request);
            log.info("Payout request {} successfully processed on RazorpayX", request.getId());
        } else if ("payout.failed".equalsIgnoreCase(eventType) || "payout.reversed".equalsIgnoreCase(eventType)) {
            if (request.getStatus() != PayoutRequestStatus.FAILED) {
                request.setStatus(PayoutRequestStatus.FAILED);
                String errorReason = (String) payoutData.get("status_details");
                request.setFailureReason(errorReason != null ? errorReason : "Payout transfer failed/reversed");
                payoutRequestRepository.save(request);

                // Refund user wallet balance
                walletService.credit(
                        request.getUserId(),
                        request.getAmount(),
                        WalletTransactionReason.PAYOUT_FAILED_REFUND,
                        request.getId(),
                        "Refund for failed/reversed payout transfer #" + request.getId());
                log.info("Payout request {} failed on RazorpayX -- wallet refunded", request.getId());
            }
        }
    }

    private PayoutResponseDto mapToDto(PayoutRequest request, UserBankAccount bankAccount) {
        UserBankAccountDto bankDto = bankAccount != null ? UserBankAccountDto.builder()
                .id(bankAccount.getId())
                .userId(bankAccount.getUserId())
                .accountHolderName(bankAccount.getAccountHolderName())
                .accountNumber(bankAccount.getAccountNumber())
                .ifscCode(bankAccount.getIfscCode())
                .bankName(bankAccount.getBankName())
                .upiId(bankAccount.getUpiId())
                .build() : null;

        return PayoutResponseDto.builder()
                .id(request.getId())
                .userId(request.getUserId())
                .userRole(request.getUserRole())
                .amount(request.getAmount())
                .status(request.getStatus())
                .razorpayPayoutId(request.getRazorpayPayoutId())
                .adminNotes(request.getAdminNotes())
                .failureReason(request.getFailureReason())
                .bankAccount(bankDto)
                .processedAt(request.getProcessedAt())
                .createdAt(request.getCreatedAt())
                .build();
    }
}
