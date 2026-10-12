package com.veggofresh.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Business address and service area as entered during vendor onboarding. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorKycAddressDto {
    private String fullAddress;
    private String streetAddress;
    private String city;
    private String state;
    private String zipCode;
    private Double latitude;
    private Double longitude;
    private Double deliveryRangeKm;
}
