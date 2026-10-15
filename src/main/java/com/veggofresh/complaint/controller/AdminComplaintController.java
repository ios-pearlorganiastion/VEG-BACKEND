package com.veggofresh.complaint.controller;

import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.complaint.dto.AdminUpdateComplaintStatusDto;
import com.veggofresh.complaint.dto.ComplaintResponseDto;
import com.veggofresh.complaint.dto.ComplaintStatsResponseDto;
import com.veggofresh.complaint.entity.ComplaintStatus;
import com.veggofresh.complaint.service.ComplaintService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin management portal for complaints across all user types (Customer, Vendor, Delivery).
 * Requires ADMIN role.
 */
@RestController
@RequestMapping("/api/admin/complaints")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminComplaintController {

    private final ComplaintService complaintService;

    /**
     * List all complaints across the platform with optional filters for status, role, and keyword search.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ComplaintResponseDto>>> getAllComplaints(
            @RequestParam(required = false) ComplaintStatus status,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ComplaintResponseDto> result = complaintService.adminGetComplaints(status, role, search, pageRequest);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(result), "Complaints retrieved successfully"));
    }

    /**
     * Get complaint dashboard metrics (counts for total, pending, resolved, unresolved, reopened, role breakdown).
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<ComplaintStatsResponseDto>> getStats() {
        ComplaintStatsResponseDto stats = complaintService.adminGetStats();
        return ResponseEntity.ok(ApiResponse.success(stats, "Complaint statistics retrieved successfully"));
    }

    /**
     * View detailed complaint information including complainant profile and linked order details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ComplaintResponseDto>> getComplaintDetails(@PathVariable UUID id) {
        ComplaintResponseDto response = complaintService.getComplaintById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Complaint details retrieved successfully"));
    }

    /**
     * Mark a complaint as RESOLVED or UNRESOLVED and optionally provide admin resolution notes.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ComplaintResponseDto>> updateComplaintStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AdminUpdateComplaintStatusDto request) {
        ComplaintResponseDto response = complaintService.adminUpdateStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Complaint status updated to " + response.getStatus()));
    }
}
