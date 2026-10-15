package com.veggofresh.complaint.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload when a user reopens a resolved complaint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReopenComplaintRequestDto {

    /**
     * Explanation or reason why the user is reopening the complaint.
     */
    private String reason;
}
