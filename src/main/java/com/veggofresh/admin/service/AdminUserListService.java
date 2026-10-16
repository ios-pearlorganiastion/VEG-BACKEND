package com.veggofresh.admin.service;

import com.veggofresh.admin.dto.AdminCustomerDto;
import com.veggofresh.admin.dto.AdminDeliveryPartnerDto;
import com.veggofresh.admin.dto.AdminVendorDto;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.vendor.entity.KycStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Admin service for platform-wide user management:
 *  - Get All Vendors (with filters, sorting, pagination)
 *  - Get All Customers (with filters, sorting, pagination)
 *  - Get All Delivery Partners (with filters, sorting, pagination)
 *  - PDF export for each list
 */
public interface AdminUserListService {

    // ── Vendors ─────────────────────────────────────────────────────────────

    /**
     * Paginated + filtered vendor list.
     *
     * @param search    optional text search (shop name / owner name / email / phone / city)
     * @param kycStatus optional KYC status filter
     * @param isOnline  optional online/offline filter
     * @param pageable  pagination + sorting (createdAt ASC/DESC supported)
     */
    Page<AdminVendorDto> getAllVendors(String search, KycStatus kycStatus, Boolean isOnline, Pageable pageable);

    /**
     * Returns the matching vendor rows as a flat list for PDF generation.
     * Internally limited to {@code limit} records to avoid OOM.
     */
    List<AdminVendorDto> getVendorsForExport(String search, KycStatus kycStatus, Boolean isOnline, int limit);

    /** Generates a PDF byte array from the given vendor list. */
    byte[] generateVendorsPdf(List<AdminVendorDto> vendors);

    // ── Customers ────────────────────────────────────────────────────────────

    /**
     * Paginated + filtered customer list.
     *
     * @param search     optional text search (name / email / phone)
     * @param isBlocked  optional blocked-state filter
     * @param isVerified optional verified-state filter
     * @param pageable   pagination + sorting
     */
    Page<AdminCustomerDto> getAllCustomers(String search, Boolean isBlocked, Boolean isVerified, Pageable pageable);

    /**
     * Returns the matching customer rows as a flat list for PDF generation.
     * Internally limited to {@code limit} records to avoid OOM.
     */
    List<AdminCustomerDto> getCustomersForExport(String search, Boolean isBlocked, Boolean isVerified, int limit);

    /** Generates a PDF byte array from the given customer list. */
    byte[] generateCustomersPdf(List<AdminCustomerDto> customers);

    // ── Delivery Partners ────────────────────────────────────────────────────

    /**
     * Paginated + filtered delivery-partner list.
     *
     * @param search     optional text search (name / email / city / vehicle / plate)
     * @param kycStatus  optional KYC status filter
     * @param isOnline   optional online/offline filter
     * @param pageable   pagination + sorting
     */
    Page<AdminDeliveryPartnerDto> getAllDeliveryPartners(String search, DeliveryKycStatus kycStatus, Boolean isOnline, Pageable pageable);

    /**
     * Returns the matching delivery-partner rows as a flat list for PDF generation.
     * Internally limited to {@code limit} records.
     */
    List<AdminDeliveryPartnerDto> getDeliveryPartnersForExport(String search, DeliveryKycStatus kycStatus, Boolean isOnline, int limit);

    /** Generates a PDF byte array from the given delivery-partner list. */
    byte[] generateDeliveryPartnersPdf(List<AdminDeliveryPartnerDto> partners);
}
