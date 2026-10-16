package com.veggofresh.admin.controller;

import com.veggofresh.admin.dto.AdminCustomerDto;
import com.veggofresh.admin.dto.AdminDeliveryPartnerDto;
import com.veggofresh.admin.dto.AdminVendorDto;
import com.veggofresh.admin.service.AdminUserListService;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.vendor.entity.KycStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin controller for platform-wide user management:
 *
 * <pre>
 * ── Vendors ───────────────────────────────────────────────────────────────
 * GET  /api/admin/users/vendors              → paginated + filtered vendor list
 * GET  /api/admin/users/vendors/export/pdf   → download matched vendors as PDF
 *
 * ── Customers ─────────────────────────────────────────────────────────────
 * GET  /api/admin/users/customers            → paginated + filtered customer list
 * GET  /api/admin/users/customers/export/pdf → download matched customers as PDF
 *
 * ── Delivery Partners ─────────────────────────────────────────────────────
 * GET  /api/admin/users/delivery             → paginated + filtered delivery list
 * GET  /api/admin/users/delivery/export/pdf  → download matched delivery partners as PDF
 * </pre>
 *
 * <h3>Common query params (all list endpoints)</h3>
 * <ul>
 *   <li>{@code search}  — free-text search across name / email / phone / city etc.</li>
 *   <li>{@code page}    — 0-indexed page number (default 0)</li>
 *   <li>{@code size}    — page size, max 100 (default 20)</li>
 *   <li>{@code sort}    — {@code newest} (default) | {@code oldest}</li>
 * </ul>
 *
 * <h3>Vendor-specific params</h3>
 * <ul>
 *   <li>{@code kycStatus} — PENDING | APPROVED | REJECTED (optional)</li>
 *   <li>{@code isOnline}  — true | false (optional)</li>
 * </ul>
 *
 * <h3>Customer-specific params</h3>
 * <ul>
 *   <li>{@code isBlocked}  — true | false (optional)</li>
 *   <li>{@code isVerified} — true | false (optional)</li>
 * </ul>
 *
 * <h3>Delivery-specific params</h3>
 * <ul>
 *   <li>{@code kycStatus} — PENDING | APPROVED | REJECTED (optional)</li>
 *   <li>{@code isOnline}  — true | false (optional)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserListController {

    private static final int MAX_SIZE = 100;
    private static final int PDF_EXPORT_LIMIT = 500;

    private final AdminUserListService adminUserListService;

    // ─────────────────────────────────────────────────────────────────────────
    // VENDORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/users/vendors
     * Returns a paginated, filtered, sortable list of all vendors.
     *
     * @param search    free-text search (shop name / owner name / email / phone / city)
     * @param kycStatus filter by KYC status (PENDING / APPROVED / REJECTED)
     * @param isOnline  filter by online state (true/false)
     * @param page      page index (0-based)
     * @param size      page size (max 100)
     * @param sort      "newest" (default, createdAt DESC) or "oldest" (createdAt ASC)
     */
    @GetMapping("/vendors")
    public ResponseEntity<ApiResponse<PageResponse<AdminVendorDto>>> getAllVendors(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) Boolean isOnline,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort) {

        PageRequest pageRequest = buildPageRequest(page, size, sort);
        Page<AdminVendorDto> result = adminUserListService.getAllVendors(search, kycStatus, isOnline, pageRequest);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(result),
                "Vendors retrieved successfully"));
    }

    /**
     * GET /api/admin/users/vendors/export/pdf
     * Downloads the current filtered vendor list as a PDF file.
     * Applies the same filters as the list endpoint; exports up to 500 records.
     */
    @GetMapping("/vendors/export/pdf")
    public ResponseEntity<byte[]> exportVendorsPdf(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) Boolean isOnline,
            @RequestParam(defaultValue = "500") int limit) {

        List<AdminVendorDto> vendors = adminUserListService.getVendorsForExport(search, kycStatus, isOnline, limit);
        byte[] pdf = adminUserListService.generateVendorsPdf(vendors);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"veggofresh_vendors.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOMERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/users/customers
     * Returns a paginated, filtered, sortable list of all customers.
     *
     * @param search     free-text search (name / email / phone)
     * @param isBlocked  filter blocked/unblocked accounts
     * @param isVerified filter verified/unverified accounts
     * @param page       page index (0-based)
     * @param size       page size (max 100)
     * @param sort       "newest" (default) or "oldest"
     */
    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<PageResponse<AdminCustomerDto>>> getAllCustomers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isBlocked,
            @RequestParam(required = false) Boolean isVerified,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort) {

        PageRequest pageRequest = buildPageRequest(page, size, sort);
        Page<AdminCustomerDto> result = adminUserListService.getAllCustomers(search, isBlocked, isVerified, pageRequest);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(result),
                "Customers retrieved successfully"));
    }

    /**
     * GET /api/admin/users/customers/export/pdf
     * Downloads the current filtered customer list as a PDF file.
     * Applies the same filters as the list endpoint; exports up to 500 records.
     */
    @GetMapping("/customers/export/pdf")
    public ResponseEntity<byte[]> exportCustomersPdf(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isBlocked,
            @RequestParam(required = false) Boolean isVerified,
            @RequestParam(defaultValue = "500") int limit) {

        List<AdminCustomerDto> customers = adminUserListService.getCustomersForExport(search, isBlocked, isVerified, limit);
        byte[] pdf = adminUserListService.generateCustomersPdf(customers);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"veggofresh_customers.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELIVERY PARTNERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/users/delivery
     * Returns a paginated, filtered, sortable list of all delivery partners.
     *
     * @param search    free-text search (name / email / city / vehicle type / plate)
     * @param kycStatus filter by KYC status
     * @param isOnline  filter by online state
     * @param page      page index (0-based)
     * @param size      page size (max 100)
     * @param sort      "newest" (default) or "oldest"
     */
    @GetMapping("/delivery")
    public ResponseEntity<ApiResponse<PageResponse<AdminDeliveryPartnerDto>>> getAllDeliveryPartners(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) DeliveryKycStatus kycStatus,
            @RequestParam(required = false) Boolean isOnline,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort) {

        PageRequest pageRequest = buildPageRequest(page, size, sort);
        Page<AdminDeliveryPartnerDto> result = adminUserListService.getAllDeliveryPartners(search, kycStatus, isOnline, pageRequest);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(result),
                "Delivery partners retrieved successfully"));
    }

    /**
     * GET /api/admin/users/delivery/export/pdf
     * Downloads the current filtered delivery-partner list as a PDF file.
     * Applies the same filters as the list endpoint; exports up to 500 records.
     */
    @GetMapping("/delivery/export/pdf")
    public ResponseEntity<byte[]> exportDeliveryPartnersPdf(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) DeliveryKycStatus kycStatus,
            @RequestParam(required = false) Boolean isOnline,
            @RequestParam(defaultValue = "500") int limit) {

        List<AdminDeliveryPartnerDto> partners = adminUserListService.getDeliveryPartnersForExport(search, kycStatus, isOnline, limit);
        byte[] pdf = adminUserListService.generateDeliveryPartnersPdf(partners);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"veggofresh_delivery_partners.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a PageRequest with bounded size and sort direction.
     * "newest" → createdAt DESC (default)
     * "oldest" → createdAt ASC
     */
    private PageRequest buildPageRequest(int page, int size, String sort) {
        Sort.Direction direction = "oldest".equalsIgnoreCase(sort)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return PageRequest.of(page, Math.min(size, MAX_SIZE), Sort.by(direction, "createdAt"));
    }
}
