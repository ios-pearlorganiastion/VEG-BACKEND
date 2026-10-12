package com.veggofresh.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Which onboarding steps the vendor has completed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorKycProgressDto {
    private boolean basicInfoCompleted;
    private boolean businessLocationCompleted;
    private boolean bankDetailsCompleted;
    private boolean paymentSettingsConfigured;
}
