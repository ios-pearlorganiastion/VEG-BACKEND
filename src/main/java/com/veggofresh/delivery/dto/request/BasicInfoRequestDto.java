package com.veggofresh.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Signup form (name and city), captured right after OTP verification. The vehicle type is no longer asked here:
 * it is entered together with the rest of the vehicle details in verification step 2.
 */
@Getter
@Setter
public class BasicInfoRequestDto {
    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "City of operation is required")
    private String cityOfOperation;
}
