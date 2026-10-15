package com.veggofresh.complaint.controller;

import com.veggofresh.complaint.dto.ComplaintResponseDto;
import com.veggofresh.complaint.dto.CreateComplaintRequestDto;
import com.veggofresh.complaint.dto.ReopenComplaintRequestDto;
import com.veggofresh.complaint.entity.ComplaintCategory;
import com.veggofresh.complaint.entity.ComplaintStatus;
import com.veggofresh.complaint.service.ComplaintService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Complaint endpoints for Customers, Vendors, and Delivery partners.
 * Identity is determined strictly by the Bearer JWT token.
 */
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;
    private final CloudinaryService cloudinaryService;

    /**
     * File/Register a new complaint using multipart/form-data.
     * The caller's identity (user ID & role) is extracted directly from the Bearer token.
     * Attachments are actual image/document files uploaded via Cloudinary.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ComplaintResponseDto>> registerComplaint(
            @RequestParam("category") ComplaintCategory category,
            @RequestParam("title") String title,
            @RequestParam("description") String description,
            @RequestParam(required = false, name = "orderId") UUID orderId,
            @RequestParam(required = false, name = "attachments") List<MultipartFile> attachmentFiles) {

        // Upload each provided attachment to Cloudinary and collect public URLs
        List<String> attachmentUrls = new ArrayList<>();
        if (attachmentFiles != null) {
            for (MultipartFile file : attachmentFiles) {
                if (file != null && !file.isEmpty()) {
                    CloudinaryUploadResult result = cloudinaryService.uploadImage(
                            file, "veggofresh/complaints");
                    attachmentUrls.add(result.url());
                }
            }
        }

        CreateComplaintRequestDto request = CreateComplaintRequestDto.builder()
                .category(category)
                .title(title.trim())
                .description(description.trim())
                .orderId(orderId)
                .attachments(attachmentUrls)
                .build();

        ComplaintResponseDto response = complaintService.createComplaint(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Complaint submitted successfully"));
    }

    /**
     * Retrieve all complaints submitted by the authenticated user.
     * Supports optional status filtering and pagination.
     */
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<ComplaintResponseDto>>> getMyComplaints(
            @RequestParam(required = false) ComplaintStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ComplaintResponseDto> result = complaintService.getMyComplaints(status, pageRequest);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(result), "Complaints retrieved successfully"));
    }

    /**
     * Get complaint details by ID.
     * Only the complainant or Admin can view.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ComplaintResponseDto>> getComplaintById(@PathVariable UUID id) {
        ComplaintResponseDto response = complaintService.getComplaintById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Complaint details retrieved successfully"));
    }

    /**
     * Reopen a resolved complaint.
     * Can only be called by the user who registered it, and only if the status is currently RESOLVED.
     */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<ApiResponse<ComplaintResponseDto>> reopenComplaint(
            @PathVariable UUID id,
            @RequestBody(required = false) ReopenComplaintRequestDto request) {
        ComplaintResponseDto response = complaintService.reopenComplaint(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Complaint reopened successfully"));
    }
}
