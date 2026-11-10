package com.veggofresh.delivery.service;

import com.veggofresh.delivery.dto.request.AccountSettingsRequestDto;
import com.veggofresh.delivery.dto.request.DeliveryLocationStatusRequestDto;
import com.veggofresh.delivery.dto.request.DeliveryProfileRequestDto;
import com.veggofresh.delivery.dto.response.AccountSettingsResponseDto;
import com.veggofresh.delivery.dto.response.DeliveryProfileResponseDto;

import java.util.UUID;

public interface DeliveryProfileService {
    DeliveryProfileResponseDto getOrCreateProfile(UUID userId);
    DeliveryProfileResponseDto updateProfile(UUID userId, DeliveryProfileRequestDto request);
    DeliveryProfileResponseDto updateStatus(UUID userId, DeliveryLocationStatusRequestDto request);

    /** TEST-ONLY. Force-approves KYC so the online/accept/pickup flow is testable
     *  before the Admin module's real approval endpoint exists. Remove before prod. */
    DeliveryProfileResponseDto approveKycForTesting(UUID userId);

    AccountSettingsResponseDto getAccountSettings(UUID userId);
    AccountSettingsResponseDto updateAccountSettings(UUID userId, AccountSettingsRequestDto request);
}
