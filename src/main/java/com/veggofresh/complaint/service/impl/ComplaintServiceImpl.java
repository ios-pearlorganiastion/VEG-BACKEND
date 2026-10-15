package com.veggofresh.complaint.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veggofresh.auth.entity.User;
import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.auth.repository.UserRepository;
import com.veggofresh.complaint.dto.AdminUpdateComplaintStatusDto;
import com.veggofresh.complaint.dto.ComplaintResponseDto;
import com.veggofresh.complaint.dto.ComplaintStatsResponseDto;
import com.veggofresh.complaint.dto.CreateComplaintRequestDto;
import com.veggofresh.complaint.dto.ReopenComplaintRequestDto;
import com.veggofresh.complaint.entity.Complaint;
import com.veggofresh.complaint.entity.ComplaintStatus;
import com.veggofresh.complaint.repository.ComplaintRepository;
import com.veggofresh.complaint.service.ComplaintService;
import com.veggofresh.customer.entity.CustomerProfile;
import com.veggofresh.customer.entity.Order;
import com.veggofresh.customer.repository.CustomerProfileRepository;
import com.veggofresh.customer.repository.OrderRepository;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.security.SecurityUtils;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComplaintServiceImpl implements ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final ShopRepository shopRepository;
    private final DeliveryPartnerProfileRepository deliveryPartnerProfileRepository;
    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    private static final String TICKET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    @Transactional
    public ComplaintResponseDto createComplaint(CreateComplaintRequestDto request) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        String roleStr = SecurityUtils.getCurrentUserRole();

        UserRole userRole;
        try {
            userRole = UserRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ROLE", "Unrecognized user role: " + roleStr, HttpStatus.BAD_REQUEST);
        }

        // Verify the user exists in database
        userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Authenticated user not found", HttpStatus.NOT_FOUND));

        // Validate orderId if provided
        if (request.getOrderId() != null) {
            orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Referenced order not found", HttpStatus.NOT_FOUND));
        }

        Complaint complaint = new Complaint();
        complaint.setTicketNumber(generateUniqueTicketNumber());
        complaint.setUserId(currentUserId);
        complaint.setUserRole(userRole);
        complaint.setCategory(request.getCategory());
        complaint.setTitle(request.getTitle().trim());
        complaint.setDescription(request.getDescription().trim());
        complaint.setOrderId(request.getOrderId());
        complaint.setStatus(ComplaintStatus.PENDING);

        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            try {
                complaint.setAttachments(objectMapper.writeValueAsString(request.getAttachments()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize complaint attachments", e);
                complaint.setAttachments(String.join(",", request.getAttachments()));
            }
        }

        Complaint saved = complaintRepository.save(complaint);
        log.info("New complaint created: ticket={}, userId={}, role={}", saved.getTicketNumber(), currentUserId, userRole);

        return mapToDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ComplaintResponseDto> getMyComplaints(ComplaintStatus status, Pageable pageable) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        Page<Complaint> page;
        if (status != null) {
            page = complaintRepository.findByUserIdAndStatus(currentUserId, status, pageable);
        } else {
            page = complaintRepository.findByUserId(currentUserId, pageable);
        }
        return page.map(this::mapToDto);
    }

    @Override
    @Transactional(readOnly = true)
    public ComplaintResponseDto getComplaintById(UUID complaintId) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException("COMPLAINT_NOT_FOUND", "Complaint not found", HttpStatus.NOT_FOUND));

        UUID currentUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = SecurityUtils.hasRole("ADMIN");

        if (!isAdmin && !complaint.getUserId().equals(currentUserId)) {
            throw new BusinessException("FORBIDDEN", "You do not have access to this complaint", HttpStatus.FORBIDDEN);
        }

        return mapToDto(complaint);
    }

    @Override
    @Transactional
    public ComplaintResponseDto reopenComplaint(UUID complaintId, ReopenComplaintRequestDto request) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException("COMPLAINT_NOT_FOUND", "Complaint not found", HttpStatus.NOT_FOUND));

        // Security check: Only the owner who registered this complaint can reopen it
        if (!complaint.getUserId().equals(currentUserId)) {
            throw new BusinessException("FORBIDDEN", "Only the user who filed this complaint can reopen it", HttpStatus.FORBIDDEN);
        }

        // Rule: Only complaints in RESOLVED status can be reopened
        if (complaint.getStatus() != ComplaintStatus.RESOLVED) {
            throw new BusinessException("COMPLAINT_CANNOT_REOPEN",
                    "Only complaints marked as RESOLVED can be reopened. Current status: " + complaint.getStatus(),
                    HttpStatus.BAD_REQUEST);
        }

        complaint.setStatus(ComplaintStatus.REOPENED);
        String reason = (request != null && request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason().trim()
                : "Complainant indicated the issue was not fully resolved.";
        complaint.setReopenReason(reason);
        complaint.setReopenedAt(Instant.now());

        Complaint updated = complaintRepository.save(complaint);
        log.info("Complaint reopened: ticket={}, userId={}", updated.getTicketNumber(), currentUserId);

        return mapToDto(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ComplaintResponseDto> adminGetComplaints(ComplaintStatus status, UserRole role, String search, Pageable pageable) {
        String cleanSearch = (search != null && !search.trim().isEmpty()) ? search.trim() : null;
        Page<Complaint> page = complaintRepository.findByFilters(status, role, cleanSearch, pageable);
        return page.map(this::mapToDto);
    }

    @Override
    @Transactional
    public ComplaintResponseDto adminUpdateStatus(UUID complaintId, AdminUpdateComplaintStatusDto request) {
        UUID adminId = SecurityUtils.getCurrentUserId();

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException("COMPLAINT_NOT_FOUND", "Complaint not found", HttpStatus.NOT_FOUND));

        if (request.getStatus() != ComplaintStatus.RESOLVED && request.getStatus() != ComplaintStatus.UNRESOLVED) {
            throw new BusinessException("INVALID_STATUS",
                    "Admin can only set status to RESOLVED or UNRESOLVED. Provided: " + request.getStatus(),
                    HttpStatus.BAD_REQUEST);
        }

        complaint.setStatus(request.getStatus());
        if (request.getAdminNotes() != null) {
            complaint.setAdminNotes(request.getAdminNotes().trim());
        }
        complaint.setResolvedAt(Instant.now());
        complaint.setResolvedBy(adminId);

        Complaint updated = complaintRepository.save(complaint);
        log.info("Admin updated complaint status: ticket={}, newStatus={}, adminId={}",
                updated.getTicketNumber(), updated.getStatus(), adminId);

        // Notify complainant about resolution or unresolution
        notifyComplainantOnStatusChange(updated);

        return mapToDto(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public ComplaintStatsResponseDto adminGetStats() {
        return ComplaintStatsResponseDto.builder()
                .totalComplaints(complaintRepository.count())
                .pendingComplaints(complaintRepository.countByStatus(ComplaintStatus.PENDING))
                .resolvedComplaints(complaintRepository.countByStatus(ComplaintStatus.RESOLVED))
                .unresolvedComplaints(complaintRepository.countByStatus(ComplaintStatus.UNRESOLVED))
                .reopenedComplaints(complaintRepository.countByStatus(ComplaintStatus.REOPENED))
                .customerComplaints(complaintRepository.countByUserRole(UserRole.CUSTOMER))
                .vendorComplaints(complaintRepository.countByUserRole(UserRole.VENDOR))
                .deliveryComplaints(complaintRepository.countByUserRole(UserRole.DELIVERY))
                .build();
    }

    private void notifyComplainantOnStatusChange(Complaint complaint) {
        try {
            NotificationRecipientRole recipientRole = NotificationRecipientRole.valueOf(complaint.getUserRole().name());
            boolean isResolved = complaint.getStatus() == ComplaintStatus.RESOLVED;

            NotificationType type = isResolved ? NotificationType.COMPLAINT_RESOLVED : NotificationType.COMPLAINT_UNRESOLVED;
            String title = isResolved
                    ? "Complaint #" + complaint.getTicketNumber() + " Resolved"
                    : "Complaint #" + complaint.getTicketNumber() + " Unresolved";

            String body = isResolved
                    ? "Your complaint regarding '" + complaint.getTitle() + "' was marked as resolved. You can reopen it if needed."
                    : "Your complaint regarding '" + complaint.getTitle() + "' was marked as unresolved."
                    + (complaint.getAdminNotes() != null ? " Note: " + complaint.getAdminNotes() : "");

            String dataJson = String.format("{\"complaintId\":\"%s\",\"ticketNumber\":\"%s\",\"status\":\"%s\"}",
                    complaint.getId(), complaint.getTicketNumber(), complaint.getStatus());

            notificationService.send(complaint.getUserId(), recipientRole, type, title, body, dataJson);
        } catch (Exception e) {
            log.warn("Failed to send notification for complaint status update: {}", complaint.getTicketNumber(), e);
        }
    }

    private String generateUniqueTicketNumber() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(TICKET_ALPHABET.charAt(RANDOM.nextInt(TICKET_ALPHABET.length())));
            }
            String candidate = "CMP-" + datePart + "-" + sb;
            if (complaintRepository.findByTicketNumber(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "CMP-" + datePart + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private ComplaintResponseDto mapToDto(Complaint complaint) {
        ComplaintResponseDto.ComplaintResponseDtoBuilder builder = ComplaintResponseDto.builder()
                .id(complaint.getId())
                .ticketNumber(complaint.getTicketNumber())
                .createdAt(complaint.getCreatedAt())
                .updatedAt(complaint.getUpdatedAt())
                .userId(complaint.getUserId())
                .userRole(complaint.getUserRole())
                .category(complaint.getCategory())
                .title(complaint.getTitle())
                .description(complaint.getDescription())
                .status(complaint.getStatus())
                .canReopen(complaint.getStatus() == ComplaintStatus.RESOLVED)
                .adminNotes(complaint.getAdminNotes())
                .resolvedAt(complaint.getResolvedAt())
                .resolvedBy(complaint.getResolvedBy())
                .reopenReason(complaint.getReopenReason())
                .reopenedAt(complaint.getReopenedAt())
                .orderId(complaint.getOrderId());

        // Parse attachments
        if (complaint.getAttachments() != null && !complaint.getAttachments().isBlank()) {
            try {
                List<String> list = objectMapper.readValue(complaint.getAttachments(), new TypeReference<List<String>>() {});
                builder.attachments(list);
            } catch (Exception e) {
                builder.attachments(List.of(complaint.getAttachments().split(",")));
            }
        } else {
            builder.attachments(new ArrayList<>());
        }

        // Hydrate user profile details
        hydrateComplainantInfo(builder, complaint.getUserId(), complaint.getUserRole());

        // Hydrate order summary if orderId is linked
        if (complaint.getOrderId() != null) {
            Optional<Order> orderOpt = orderRepository.findById(complaint.getOrderId());
            if (orderOpt.isPresent()) {
                Order order = orderOpt.get();
                builder.orderNumber(order.getOrderNumber());
                builder.orderStatus(order.getStatus() != null ? order.getStatus().name() : null);
                builder.orderAmount(order.getTotalAmount());
            }
        }

        return builder.build();
    }

    private void hydrateComplainantInfo(ComplaintResponseDto.ComplaintResponseDtoBuilder builder, UUID userId, UserRole role) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            builder.userPhone(user.getPhone());
            builder.userEmail(user.getEmail());
        }

        if (role == UserRole.CUSTOMER) {
            customerProfileRepository.findByUserId(userId).ifPresent(cp -> {
                builder.userName(cp.getFullName());
                if (cp.getEmail() != null && !cp.getEmail().isBlank()) {
                    builder.userEmail(cp.getEmail());
                }
            });
        } else if (role == UserRole.VENDOR) {
            shopRepository.findByOwnerUserIdAndDeletedAtIsNull(userId).ifPresent(shop -> {
                builder.userName(shop.getName() != null ? shop.getName() : shop.getFullName());
                if (shop.getEmail() != null && !shop.getEmail().isBlank()) {
                    builder.userEmail(shop.getEmail());
                }
                if (shop.getBusinessPhone() != null && !shop.getBusinessPhone().isBlank()) {
                    builder.userPhone(shop.getBusinessPhone());
                }
            });
        } else if (role == UserRole.DELIVERY) {
            deliveryPartnerProfileRepository.findByUserId(userId).ifPresent(dp -> {
                builder.userName(dp.getFullName());
                if (dp.getEmail() != null && !dp.getEmail().isBlank()) {
                    builder.userEmail(dp.getEmail());
                }
            });
        }
    }
}
