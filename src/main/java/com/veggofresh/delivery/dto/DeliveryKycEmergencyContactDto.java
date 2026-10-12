package com.veggofresh.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Emergency contact entered during delivery partner onboarding. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryKycEmergencyContactDto {
    private String name;
    private String relationship;
    private String phone;
}
