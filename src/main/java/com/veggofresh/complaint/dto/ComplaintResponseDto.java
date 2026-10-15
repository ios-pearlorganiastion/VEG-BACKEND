package com.veggofresh.complaint.dto;

import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.complaint.entity.ComplaintCategory;
import com.veggofresh.complaint.entity.ComplaintStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full DTO response representing a complaint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintResponseDto {

    private UUID id;
    private String ticketNumber;
    private Instant createdAt;
    private Instant updatedAt;

    // Complainant Details (resolved from token & user repositories)
    private UUID userId;
    private UserRole userRole;
    private String userName;
    private String userPhone;
    private String userEmail;

    // Complaint Content
    private ComplaintCategory category;
    private String title;
    private String description;
    private List<String> attachments;

    // Associated Order Info (if applicable)
    private UUID orderId;
    private String orderNumber;
    private String orderStatus;
    private BigDecimal orderAmount;

    // Status & Lifecycle
    private ComplaintStatus status;
    private boolean canReopen;

    // Admin Resolution Details
    private String adminNotes;
    private Instant resolvedAt;
    private UUID resolvedBy;

    // Reopen Details
    private String reopenReason;
    private Instant reopenedAt;
}
