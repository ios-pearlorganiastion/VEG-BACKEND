package com.veggofresh.delivery.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One line of a rider's review history, shown to the admin. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryKycHistoryDto {

    /** SUBMITTED, WITHDRAWN, APPROVED or REJECTED. */
    private String action;

    /** Which submission this belongs to (1 = the first time the rider submitted). */
    private int submissionNumber;

    /** The rejection reason; null for other actions. */
    private String reason;

    /** Sections sent back by a rejection (BASIC_INFO, LICENSE, VEHICLE, BANK); empty otherwise. */
    private List<String> sections;

    /** The admin who decided; null for the rider's own actions. */
    private UUID adminUserId;

    private Instant at;
}
