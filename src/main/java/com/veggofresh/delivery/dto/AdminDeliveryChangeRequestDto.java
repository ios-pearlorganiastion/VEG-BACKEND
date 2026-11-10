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

/** A change request as the admin sees it: who, what changed (old vs new), the documents, and the decision. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDeliveryChangeRequestDto {
    private UUID id;
    private UUID userId;
    private String fullName;
    private String phone;

    private DeliveryKycSection section;
    private DeliveryChangeRequestStatus status;
    private Instant submittedAt;
    private Instant decidedAt;
    private UUID decidedBy;
    private String reason;

    /** What the profile held when the rider made the request. */
    private Map<String, String> previous;

    /** What the profile holds NOW (differs from {@link #previous} only if something else changed it since). */
    private Map<String, String> current;

    /** What the rider is asking for. */
    private Map<String, String> requested;

    /** The new document attached to the request; null for BASIC_INFO. */
    private String documentUrl;

    /** The document that is live today (what approval would replace); null if none. */
    private String currentDocumentUrl;

    /**
     * BANK requests only: how many payout requests of this rider are still waiting for approval. Approval is
     * refused while this is above zero, so those payouts are never redirected to the new account.
     */
    private Integer pendingPayoutRequests;
}
