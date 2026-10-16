package com.veggofresh.complaint.entity;

import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a complaint filed by a Customer, Vendor, or Delivery partner.
 * All complaints go to Admin for resolution.
 */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class Complaint extends BaseEntity {

    /**
     * Unique human-readable ticket number (e.g. CMP-20261014-ABC123).
     */
    @Column(name = "ticket_number", nullable = false, unique = true, length = 40)
    private String ticketNumber;

    /**
     * ID of the user who filed the complaint (Customer, Vendor, or Delivery partner).
     * Sourced strictly from the caller's JWT token.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Role of the user at the time the complaint was filed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 30)
    private UserRole userRole;

    /**
     * Category of the issue.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ComplaintCategory category;

    /**
     * Short title or subject line describing the complaint.
     */
    @Column(nullable = false, length = 255)
    private String title;

    /**
     * Detailed complaint description.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    /**
     * Optional reference to a specific order ID, if the complaint is related to an order.
     */
    @Column(name = "order_id")
    private UUID orderId;

    /**
     * Optional JSON array or list of attachment URLs (e.g., photo proof, screenshots).
     */
    @Column(columnDefinition = "TEXT")
    private String attachments;

    /**
     * Current lifecycle status: PENDING, RESOLVED, UNRESOLVED, REOPENED.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ComplaintStatus status = ComplaintStatus.PENDING;

    /**
     * Admin's comments/resolution notes when reviewing or closing the ticket.
     */
    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;

    /**
     * Timestamp when the complaint was marked as resolved or unresolved by Admin.
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Admin user ID who resolved or marked unresolved the complaint.
     */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

    /**
     * Reason provided by the complainant when reopening a resolved complaint.
     */
    @Column(name = "reopen_reason", columnDefinition = "TEXT")
    private String reopenReason;

    /**
     * Timestamp when the complaint was reopened by the complainant.
     */
    @Column(name = "reopened_at")
    private Instant reopenedAt;
}
