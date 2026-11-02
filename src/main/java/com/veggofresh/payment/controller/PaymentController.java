package com.veggofresh.payment.controller;

import com.veggofresh.payment.dto.PaymentOrderStatusDto;
import com.veggofresh.payment.dto.PaymentVerifyResultDto;
import com.veggofresh.payment.dto.VerifyPaymentRequestDto;
import com.veggofresh.payment.entity.PaymentOrder;
import com.veggofresh.payment.entity.PaymentOrderLine;
import com.veggofresh.payment.repository.PaymentOrderLineRepository;
import com.veggofresh.payment.repository.PaymentOrderRepository;
import com.veggofresh.payment.service.PaymentService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Customer-facing payment endpoints:
 * - POST /api/payment/orders/verify  -- after Razorpay Checkout.js success
 * - GET  /api/payment/orders/{id}    -- status polling
 */
@RestController
@RequestMapping("/api/payment/orders")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentOrderLineRepository paymentOrderLineRepository;

    /**
     * Called by the frontend immediately after Razorpay Checkout.js reports a
     * successful payment. Verifies the HMAC-SHA256 signature, marks the PaymentOrder
     * AUTHORIZED and <b>creates the orders</b>: checkout no longer returns any, so this
     * response is where the app learns their ids.
     *
     * <p>Safe to call again (for example after a lost response): it returns the same orders and never
     * creates a second set. If the money was received but the orders could not be placed (cart changed
     * while paying), the response is a 409 with {@code ORDER_PLACEMENT_FAILED} and nothing is charged.
     */
    @PostMapping("/verify")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<PaymentVerifyResultDto>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequestDto request) {
        // From the security context, never from the request body: the ownership
        // check in PaymentServiceImpl compares against this, so a client-supplied
        // userId would defeat it.
        PaymentVerifyResultDto result = paymentService.verifyPayment(
                SecurityUtils.getCurrentUserId(),
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );
        return toResponse(result);
    }

    /** Shared by this endpoint and the {@code /api/customer/orders/verify-payment} alias. */
    public static ResponseEntity<ApiResponse<PaymentVerifyResultDto>> toResponse(PaymentVerifyResultDto result) {
        if ("PLACEMENT_FAILED".equals(result.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                    "Your payment went through but your order could not be placed: " + result.getMessage()
                            + ". You have not been charged; the amount on hold will be released.",
                    "ORDER_PLACEMENT_FAILED"));
        }
        return ResponseEntity.ok(ApiResponse.success(result, result.isOrdersPlaced()
                ? "Payment verified and your order has been placed"
                : "Payment verified"));
    }

    /**
     * Status check for a specific payment order -- lets the frontend poll for
     * AUTHORIZED/CAPTURED/VOIDED state without needing a webhook to push to it.
     */
    @GetMapping("/{paymentOrderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<PaymentOrderStatusDto>> getPaymentStatus(
            @PathVariable UUID paymentOrderId) {
        UUID userId = SecurityUtils.getCurrentUserId();

        PaymentOrder po = paymentOrderRepository.findById(paymentOrderId)
                .orElseThrow(() -> new BusinessException("PAYMENT_ORDER_NOT_FOUND",
                        "Payment order not found", HttpStatus.NOT_FOUND));

        if (!po.getUserId().equals(userId)) {
            throw new BusinessException("PAYMENT_ORDER_ACCESS_DENIED",
                    "Access denied to this payment order", HttpStatus.FORBIDDEN);
        }

        List<PaymentOrderLine> lines = paymentOrderLineRepository.findByPaymentOrderId(paymentOrderId);
        List<PaymentOrderStatusDto.PaymentOrderLineStatusDto> lineDtos = lines.stream()
                .map(l -> PaymentOrderStatusDto.PaymentOrderLineStatusDto.builder()
                        .orderId(l.getOrderId())
                        .amount(l.getAmount())
                        .status(l.getStatus().name())
                        .build())
                .collect(Collectors.toList());

        PaymentOrderStatusDto dto = PaymentOrderStatusDto.builder()
                .paymentOrderId(po.getId())
                .razorpayOrderId(po.getRazorpayOrderId())
                .status(po.getStatus().name())
                .totalAmount(po.getTotalAmount())
                .capturedAmount(po.getCapturedAmount())
                .currency(po.getCurrency())
                .authorizedAt(po.getAuthorizedAt())
                .capturedAt(po.getCapturedAt())
                .lines(lineDtos)
                .build();

        return ResponseEntity.ok(ApiResponse.success(dto, "Payment order status retrieved"));
    }
}
