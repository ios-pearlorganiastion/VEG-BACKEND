package com.veggofresh.complaint.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics response for Admin complaint dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintStatsResponseDto {

    private long totalComplaints;
    private long pendingComplaints;
    private long resolvedComplaints;
    private long unresolvedComplaints;
    private long reopenedComplaints;

    // Breakdown by role
    private long customerComplaints;
    private long vendorComplaints;
    private long deliveryComplaints;
}
