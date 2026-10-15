package com.veggofresh.complaint.dto;

import com.veggofresh.complaint.entity.ComplaintStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload sent by Admin to mark a complaint as RESOLVED or UNRESOLVED.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateComplaintStatusDto {

    /**
     * Target status: must be either RESOLVED or UNRESOLVED.
     */
    @NotNull(message = "Status is required")
    private ComplaintStatus status;

    /**
     * Admin comments or resolution notes explaining the decision.
     */
    private String adminNotes;
}
