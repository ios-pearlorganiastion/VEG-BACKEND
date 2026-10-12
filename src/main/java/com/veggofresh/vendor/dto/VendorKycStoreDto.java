package com.veggofresh.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Store profile (images, bio, attributes) entered during vendor onboarding. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorKycStoreDto {
    private String storeImageUrl;
    private String profileImageUrl;
    private String bio;
    private String attributes;
}
