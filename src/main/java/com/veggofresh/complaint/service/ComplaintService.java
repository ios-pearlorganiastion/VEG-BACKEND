package com.veggofresh.complaint.service;

import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.complaint.dto.AdminUpdateComplaintStatusDto;
import com.veggofresh.complaint.dto.ComplaintResponseDto;
import com.veggofresh.complaint.dto.ComplaintStatsResponseDto;
import com.veggofresh.complaint.dto.CreateComplaintRequestDto;
import com.veggofresh.complaint.dto.ReopenComplaintRequestDto;
import com.veggofresh.complaint.entity.ComplaintStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ComplaintService {

    /**
     * Registers a new complaint from Customer, Vendor, or Delivery partner.
     * Identity is resolved 100% from the caller's JWT token.
     */
    ComplaintResponseDto createComplaint(CreateComplaintRequestDto request);

    /**
     * Retrieves paginated complaints filed by the authenticated user.
     */
    Page<ComplaintResponseDto> getMyComplaints(ComplaintStatus status, Pageable pageable);

    /**
     * Retrieves a single complaint by ID.
     * Allowed only for the complainant or platform Admin.
     */
    ComplaintResponseDto getComplaintById(UUID complaintId);

    /**
     * Reopens a resolved complaint.
     * Allowed ONLY by the user who filed it, and ONLY if current status is RESOLVED.
     */
    ComplaintResponseDto reopenComplaint(UUID complaintId, ReopenComplaintRequestDto request);

    /**
     * Admin: List all complaints across the platform with filtering and search.
     */
    Page<ComplaintResponseDto> adminGetComplaints(ComplaintStatus status, UserRole role, String search, Pageable pageable);

    /**
     * Admin: Mark a complaint as RESOLVED or UNRESOLVED with admin notes.
     */
    ComplaintResponseDto adminUpdateStatus(UUID complaintId, AdminUpdateComplaintStatusDto request);

    /**
     * Admin: Retrieve complaint dashboard counts & statistics.
     */
    ComplaintStatsResponseDto adminGetStats();
}
