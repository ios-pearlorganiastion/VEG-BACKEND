package com.veggofresh.customer.controller;

import com.veggofresh.customer.dto.request.OrderRequestDto;
import com.veggofresh.customer.dto.request.RatingRequestDto;
import com.veggofresh.customer.dto.response.CartResponseDto;
import com.veggofresh.customer.dto.response.CheckoutResultDto;
import com.veggofresh.customer.dto.response.CheckoutSummaryDto;
import com.veggofresh.customer.dto.response.DeliverySlotDto;
import com.veggofresh.customer.dto.response.InvoiceDto;
import com.veggofresh.customer.dto.response.OrderResponseDto;
import com.veggofresh.customer.dto.response.OrderTrackingResponseDto;
import com.veggofresh.customer.dto.response.RatingResponseDto;
import com.veggofresh.customer.service.DeliverySlotService;
import com.veggofresh.customer.service.OrderService;
 import com.veggofresh.payment.dto.VerifyPaymentRequestDto;
import com.veggofresh.payment.service.PaymentService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/customer/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerOrderController {

    private final OrderService orderService;
    private final DeliverySlotService deliverySlotService;
    private final PaymentService paymentService;

    /**
     * Alias for {@code POST /api/payment/orders/verify}.
     * The Razorpay mobile SDK callback hits this URL; it simply delegates to
     * PaymentService so no separate controller is needed on the app side.
     */
    @PostMapping("/verify-payment")
    public ResponseEntity<ApiResponse<String>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequestDto request) {
        // From the security context, never from the request body -- see the
        // ownership check in PaymentServiceImpl.verifyPayment.
        paymentService.verifyPayment(
                SecurityUtils.getCurrentUserId(),
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );
        return ResponseEntity.ok(ApiResponse.success("Payment verified and authorized successfully", "OK"));
    }

    /**
     * PHASE 2 — BREAKING CHANGE: response is now {@link CheckoutResultDto}
     * (a list of orders + a list of issues) instead of a single
     * OrderResponseDto, since one checkout call can produce N orders — one
     * per open cart that still validates.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CheckoutResultDto>> checkout(
            @Valid @RequestBody OrderRequestDto request) {
        CheckoutResultDto result = orderService.checkout(SecurityUtils.getCurrentUserId(), request);
        String message = result.getIssues().isEmpty()
                ? "Order placed successfully"
                : "Order placed for available items — some carts need your attention";
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrderResponseDto>>> getOrderHistory(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt");
        Page<OrderResponseDto> history;
        if (status != null && !status.trim().isEmpty()) {
            history = orderService.getOrderHistoryByStatusGroup(SecurityUtils.getCurrentUserId(), status, PageRequest.of(page, size, newestFirst));
        } else {
            history = orderService.getOrderHistory(SecurityUtils.getCurrentUserId(), PageRequest.of(page, size, newestFirst));
        }
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(history), "Order history retrieved successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponseDto>> getOrderDetails(
            @PathVariable UUID id) {
        OrderResponseDto order = orderService.getOrderDetails(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(order, "Order details retrieved successfully"));
    }

    @GetMapping("/{id}/track")
    public ResponseEntity<ApiResponse<OrderTrackingResponseDto>> trackOrder(
            @PathVariable UUID id) {
        OrderTrackingResponseDto tracking = orderService.trackOrder(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(tracking, "Order tracking information retrieved successfully"));
    }

    /**
     * NEW -- standalone way to fetch just the drop OTP, separate from the full track
     * response above (which also includes it as a field; both read the same value).
     * Empty string until the order has been picked up -- that's when Delivery pushes
     * the real code in, not before.
     */
    @GetMapping("/{id}/drop-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> getDropOtp(@PathVariable UUID id) {
        String dropOtp = orderService.getDropOtp(SecurityUtils.getCurrentUserId(), id);
        String message = dropOtp == null
                ? "Drop OTP not yet available -- it appears once your order has been picked up"
                : "Drop OTP retrieved successfully";
        return ResponseEntity.ok(ApiResponse.success(Map.of("dropOtp", dropOtp == null ? "" : dropOtp), message));
    }

    @PostMapping("/{id}/rating")
    public ResponseEntity<ApiResponse<RatingResponseDto>> rateOrder(
            @PathVariable UUID id,
            @Valid @RequestBody RatingRequestDto request) {
        RatingResponseDto rating = orderService.rateOrder(SecurityUtils.getCurrentUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.success(rating, "Order rated successfully"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponseDto>> cancelOrder(
            @PathVariable UUID id) {
        OrderResponseDto order = orderService.cancelOrder(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(order, "Order cancelled successfully"));
    }

    /**
     * PHASE 2 — CHANGED BEHAVIOR: reorder no longer places an order directly.
     * It re-adds the original order's items back into the cart system (same
     * multi-cart logic as any add-to-cart call) and returns the resulting
     * open carts. The customer reviews and calls checkout() again, same as
     * any other cart — see OrderService.reorder() javadoc for why.
     */
    @PostMapping("/{id}/reorder")
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> reorder(
            @PathVariable UUID id) {
        List<CartResponseDto> carts = orderService.reorder(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(carts, "Order items added back to your cart — review and checkout when ready"));
    }

    @GetMapping("/{id}/invoice")
    public ResponseEntity<ApiResponse<InvoiceDto>> getInvoice(
            @PathVariable UUID id) {
        InvoiceDto invoice = orderService.getInvoice(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(invoice, "Invoice generated successfully"));
    }

    /**
     * PHASE 2 (updated) — preview for selected carts only.
     * cartIds query param is optional (comma-separated UUIDs):
     *   absent / empty → all open carts are summarised (old behaviour)
     *   present       → only the listed carts are included in the preview
     */
    @GetMapping("/checkout/summary")
    public ResponseEntity<ApiResponse<CheckoutSummaryDto>> getCheckoutSummary(
            @RequestParam UUID addressId,
            @RequestParam(required = false) List<UUID> cartIds,
            @RequestParam(required = false) String couponCode) {
        CheckoutSummaryDto summary = orderService.getCheckoutSummary(
                SecurityUtils.getCurrentUserId(), addressId, cartIds, couponCode);
        return ResponseEntity.ok(ApiResponse.success(summary, "Checkout summary retrieved successfully"));
    }

    @GetMapping("/delivery-slots")
    public ResponseEntity<ApiResponse<List<DeliverySlotDto>>> getDeliverySlots(
            @RequestParam(required = false) String date) {
        List<DeliverySlotDto> slots = deliverySlotService.getAvailableSlots(date);
        return ResponseEntity.ok(ApiResponse.success(slots, "Delivery slots retrieved successfully"));
    }
}
