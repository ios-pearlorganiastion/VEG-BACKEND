package com.veggofresh.delivery.dto;

import com.veggofresh.delivery.entity.DeliveryKycSection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rider's "verified details" screen in one response: for each section the live values, its state, and the open
 * request or last rejection if there is one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryChangeOverviewDto {

    /** True only for an approved rider. Everyone else edits the application directly. */
    private boolean canRequestChanges;

    private List<SectionOverview> sections;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectionOverview {
        private DeliveryKycSection section;

        /** VERIFIED, UNDER_REVIEW (an open request) or REJECTED (the latest request was refused). */
        private String state;

        /** The live, verified values (account number masked). */
        private Map<String, String> current;

        /** The live document for this section (license photo, insurance, bank proof); null when none. */
        private String documentUrl;

        /** The open request, present only while UNDER_REVIEW. */
        private PendingRequest pendingRequest;

        /** The latest refusal, present only while REJECTED. */
        private Rejection lastRejection;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingRequest {
        private UUID id;
        private Instant submittedAt;
        private Map<String, String> requested;
        private String documentUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rejection {
        private UUID id;
        private String reason;
        private Instant decidedAt;
    }
}
