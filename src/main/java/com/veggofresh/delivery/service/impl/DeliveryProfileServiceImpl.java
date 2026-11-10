package com.veggofresh.delivery.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.delivery.dto.request.AccountSettingsRequestDto;
import com.veggofresh.delivery.dto.request.DeliveryLocationStatusRequestDto;
import com.veggofresh.delivery.dto.request.DeliveryProfileRequestDto;
import com.veggofresh.delivery.dto.response.AccountSettingsResponseDto;
import com.veggofresh.delivery.dto.response.DeliveryProfileResponseDto;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryOnlineSession;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.repository.DeliveryOnlineSessionRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.delivery.service.DeliveryApplicationGuard;
import com.veggofresh.delivery.service.DeliveryProfileService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryProfileServiceImpl implements DeliveryProfileService {

    private final DeliveryPartnerProfileRepository profileRepository;
    private final DeliveryOnlineSessionRepository sessionRepository;
    private final UserLookupService userLookupService;
    private final CloudinaryService cloudinaryService;

    @Override
    public DeliveryProfileResponseDto getOrCreateProfile(UUID userId) {
        UserSummaryDto user = userLookupService.findById(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_USER_NOT_FOUND", "User not found in Auth module", HttpStatus.NOT_FOUND));

        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    DeliveryPartnerProfile newProfile = new DeliveryPartnerProfile();
                    newProfile.setUserId(userId);
                    return profileRepository.save(newProfile);
                });

        return mapToDto(profile, user.getPhone());
    }

    @Override
    public DeliveryProfileResponseDto updateProfile(UUID userId, DeliveryProfileRequestDto request) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        // The vehicle type is a verified detail: once the application is under review or approved it cannot be
        // changed here. Sending the value it already has is harmless and passes.
        if (request.getVehicleType() != null && !request.getVehicleType().equals(profile.getVehicleType())) {
            DeliveryApplicationGuard.requireEditable(profile, "vehicle type");
        }
        profile.setVehicleType(request.getVehicleType());
        profileRepository.save(profile);

        UserSummaryDto user = userLookupService.findById(userId).orElseThrow();
        return mapToDto(profile, user.getPhone());
    }

    @Override
    public DeliveryProfileResponseDto updateStatus(UUID userId, DeliveryLocationStatusRequestDto request) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        if (Boolean.TRUE.equals(request.getOnline()) && profile.getKycStatus() != DeliveryKycStatus.APPROVED) {
            throw new BusinessException("DELIVERY_KYC_NOT_APPROVED", "Cannot go online until KYC is approved", HttpStatus.FORBIDDEN);
        }

        boolean goingOnline = Boolean.TRUE.equals(request.getOnline());
        boolean wasOnline = profile.isOnline();

        profile.setOnline(goingOnline);
        if (request.getCurrentLatitude() != null) profile.setCurrentLatitude(request.getCurrentLatitude());
        if (request.getCurrentLongitude() != null) profile.setCurrentLongitude(request.getCurrentLongitude());
        profileRepository.save(profile);

        if (goingOnline && !wasOnline) {
            DeliveryOnlineSession session = new DeliveryOnlineSession();
            session.setDeliveryPartnerUserId(userId);
            session.setStartedAt(Instant.now());
            sessionRepository.save(session);
        } else if (!goingOnline && wasOnline) {
            sessionRepository.findByDeliveryPartnerUserIdAndEndedAtIsNull(userId)
                    .ifPresent(session -> {
                        session.setEndedAt(Instant.now());
                        sessionRepository.save(session);
                    });
        }

        UserSummaryDto user = userLookupService.findById(userId).orElseThrow();
        return mapToDto(profile, user.getPhone());
    }

    @Override
    public DeliveryProfileResponseDto approveKycForTesting(UUID userId) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        profile.setKycStatus(DeliveryKycStatus.APPROVED);
        profileRepository.save(profile);

        UserSummaryDto user = userLookupService.findById(userId).orElseThrow();
        return mapToDto(profile, user.getPhone());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSettingsResponseDto getAccountSettings(UUID userId) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        UserSummaryDto user = userLookupService.findById(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_USER_NOT_FOUND", "User not found in Auth module", HttpStatus.NOT_FOUND));

        return mapToAccountSettingsDto(profile, user.getPhone());
    }

    @Override
    public AccountSettingsResponseDto updateAccountSettings(UUID userId, AccountSettingsRequestDto request) {
        DeliveryPartnerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND", "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        // Name and vehicle type are verified details: they are locked while the application is under review and
        // once it is approved. A form that re-sends the value they already have is harmless and passes.
        if (request.getFullName() != null) {
            if (!request.getFullName().equals(profile.getFullName())) {
                DeliveryApplicationGuard.requireEditable(profile, "name");
            }
            profile.setFullName(request.getFullName());
        }
        if (request.getEmail() != null) profile.setEmail(request.getEmail());
        if (request.getVehicleType() != null) {
            if (!request.getVehicleType().equals(profile.getVehicleType())) {
                DeliveryApplicationGuard.requireEditable(profile, "vehicle type");
            }
            profile.setVehicleType(request.getVehicleType());
        }
        if (request.getVehicleColor() != null) profile.setVehicleColor(request.getVehicleColor());
        if (request.getPushNotificationsEnabled() != null) profile.setPushNotificationsEnabled(request.getPushNotificationsEnabled());
        if (request.getSmsAlertsEnabled() != null) profile.setSmsAlertsEnabled(request.getSmsAlertsEnabled());
        if (request.getEmailNewslettersEnabled() != null) profile.setEmailNewslettersEnabled(request.getEmailNewslettersEnabled());
        if (request.getEmergencyContactName() != null) profile.setEmergencyContactName(request.getEmergencyContactName());
        if (request.getEmergencyContactRelationship() != null) profile.setEmergencyContactRelationship(request.getEmergencyContactRelationship());
        if (request.getEmergencyContactPhone() != null) profile.setEmergencyContactPhone(request.getEmergencyContactPhone());

        // Avatar: optional, single, patch semantics like every other field here.
        if (request.getAvatar() != null && !request.getAvatar().isEmpty()) {
            CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                    request.getAvatar(), "veggofresh/delivery/" + userId + "/avatar");
            String oldPublicId = profile.getAvatarPublicId();
            profile.setAvatarUrl(upload.url());
            profile.setAvatarPublicId(upload.publicId());
            cloudinaryService.deleteQuietly(oldPublicId);
        }

        profileRepository.save(profile);

        UserSummaryDto user = userLookupService.findById(userId).orElseThrow();
        return mapToAccountSettingsDto(profile, user.getPhone());
    }

    private AccountSettingsResponseDto mapToAccountSettingsDto(DeliveryPartnerProfile profile, String phone) {
        return AccountSettingsResponseDto.builder()
                .fullName(profile.getFullName())
                .phone(phone)
                .email(profile.getEmail())
                .avatarUrl(profile.getAvatarUrl())
                .vehicleType(profile.getVehicleType())
                .vehicleColor(profile.getVehicleColor())
                .pushNotificationsEnabled(profile.isPushNotificationsEnabled())
                .smsAlertsEnabled(profile.isSmsAlertsEnabled())
                .emailNewslettersEnabled(profile.isEmailNewslettersEnabled())
                .emergencyContactName(profile.getEmergencyContactName())
                .emergencyContactRelationship(profile.getEmergencyContactRelationship())
                .emergencyContactPhone(profile.getEmergencyContactPhone())
                .licenseNumber(profile.getLicenseNumber())
                .plateNumber(profile.getPlateNumber())
                .vehicleModel(profile.getVehicleModel())
                .manufactureYear(profile.getManufactureYear())
                .cityOfOperation(profile.getCityOfOperation())
                .build();
    }

    private DeliveryProfileResponseDto mapToDto(DeliveryPartnerProfile profile, String phone) {
        return DeliveryProfileResponseDto.builder()
                .id(profile.getId())
                .userId(profile.getUserId())
                .phone(phone)
                .kycStatus(profile.getKycStatus())
                .online(profile.isOnline())
                .currentLatitude(profile.getCurrentLatitude())
                .currentLongitude(profile.getCurrentLongitude())
                .vehicleType(profile.getVehicleType())
                .build();
    }
}
