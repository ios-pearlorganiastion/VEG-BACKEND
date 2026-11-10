package com.veggofresh.delivery.dto;

import com.veggofresh.delivery.entity.DeliveryChangeRequestStatus;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A change request as the rider sees it. Account numbers are masked. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryChangeRequestDto {
    private UUID id;
    private DeliveryKycSection section;
    private DeliveryChangeRequestStatus status;

    /** The new values the rider asked for (account number masked). */
    private Map<String, String> requested;

    /** The new document, if the section has one. */
    private String documentUrl;

    private Instant submittedAt;

    /** When an admin decided or the rider cancelled; null while pending. */
    private Instant decidedAt;

    /** Why it was rejected; null otherwise. */
    private String reason;
}
