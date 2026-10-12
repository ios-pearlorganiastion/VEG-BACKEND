package com.veggofresh.vendor.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.VendorKycAddressDto;
import com.veggofresh.vendor.dto.VendorKycBankDto;
import com.veggofresh.vendor.dto.VendorKycDetailDto;
import com.veggofresh.vendor.dto.VendorKycDocumentDto;
import com.veggofresh.vendor.dto.VendorKycProgressDto;
import com.veggofresh.vendor.dto.VendorKycReviewDto;
import com.veggofresh.vendor.dto.VendorKycStoreDto;
import com.veggofresh.vendor.entity.KycStatus;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.entity.VendorDocument;
import com.veggofresh.vendor.entity.VendorDocumentType;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorDocumentRepository;
import com.veggofresh.vendor.service.VendorKycService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real implementation -- resolves the blocking dependency Admin's
 * AdminVendorKycController was built against. See VendorKycService javadoc and
 * NOTES_VENDOR.md for the full contract this fulfills.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VendorKycServiceImpl implements VendorKycService {

    /** Status shown for a document type the vendor has not uploaded yet. */
    private static final String NOT_UPLOADED = "NOT_UPLOADED";

    private final ShopRepository shopRepository;
    private final VendorDocumentRepository documentRepository;
    private final UserLookupService userLookupService;

    @Override
    @Transactional(readOnly = true)
    public Page<VendorKycReviewDto> listPendingKyc(Pageable pageable) {
        return shopRepository.findByKycStatusAndApplicationSubmittedAtIsNotNull(KycStatus.PENDING, pageable)
                .map(this::mapToDto);
    }

    @Override
    @Transactional(readOnly = true)
    public VendorKycDetailDto getKycDetail(UUID shopId) {
        Shop shop = getShop(shopId);

        String loginPhone = shop.getOwnerUserId() == null ? null
                : userLookupService.findById(shop.getOwnerUserId()).map(UserSummaryDto::getPhone).orElse(null);

        List<VendorKycDocumentDto> documents = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        buildDocuments(shop.getId(), documents, missing);

        return VendorKycDetailDto.builder()
                // ---- same fields as the original review response ----
                .shopId(shop.getId())
                .ownerUserId(shop.getOwnerUserId())
                .businessName(shop.getName())
                .ownerFullName(shop.getFullName())
                .businessPhone(shop.getBusinessPhone())
                .email(shop.getEmail())
                .businessType(shop.getBusinessType())
                .kycStatus(shop.getKycStatus() == null ? null : shop.getKycStatus().name())
                .rejectionReason(shop.getKycRejectionReason())
                .submittedAt(shop.getApplicationSubmittedAt())
                // ---- the rest of the onboarding data ----
                .loginPhone(loginPhone)
                .businessLicenseNumber(shop.getBusinessLicenseNumber())
                .registeredAt(shop.getCreatedAt())
                .lastUpdatedAt(shop.getUpdatedAt())
                .address(VendorKycAddressDto.builder()
                        .fullAddress(shop.getAddress())
                        .streetAddress(shop.getStreetAddress())
                        .city(shop.getCity())
                        .state(shop.getState())
                        .zipCode(shop.getZipCode())
                        .latitude(shop.getLatitude())
                        .longitude(shop.getLongitude())
                        .deliveryRangeKm(shop.getDeliveryRangeKm())
                        .build())
                .store(VendorKycStoreDto.builder()
                        .storeImageUrl(shop.getStoreImageUrl())
                        .profileImageUrl(shop.getProfileImageUrl())
                        .bio(shop.getStoreBio())
                        .attributes(shop.getStoreAttributes())
                        .build())
                .bank(VendorKycBankDto.builder()
                        .bankName(shop.getBankName())
                        .accountHolderName(shop.getAccountHolderName())
                        .accountNumber(shop.getAccountNumber())
                        .ifscCode(shop.getIfscCode())
                        .agreedToPayoutTerms(shop.isAgreedToPayoutTerms())
                        .provided(hasText(shop.getAccountNumber()) && hasText(shop.getIfscCode()))
                        .build())
                .onboardingProgress(VendorKycProgressDto.builder()
                        .basicInfoCompleted(shop.isHasBasicInfo())
                        .businessLocationCompleted(shop.isHasBusinessLocation())
                        .bankDetailsCompleted(shop.isHasBankDetails())
                        .paymentSettingsConfigured(shop.isPaymentSettingsConfigured())
                        .build())
                .documents(documents)
                .allDocumentsUploaded(missing.isEmpty())
                .missingDocuments(missing)
                .build();
    }

    @Override
    public void approveKyc(UUID shopId) {
        Shop shop = getShop(shopId);
        shop.setKycStatus(KycStatus.APPROVED);
        shop.setKycRejectionReason(null);
        shopRepository.save(shop);
    }

    @Override
    public void rejectKyc(UUID shopId, String reason) {
        Shop shop = getShop(shopId);
        shop.setKycStatus(KycStatus.REJECTED);
        shop.setKycRejectionReason(reason);
        shopRepository.save(shop);
    }

    private Shop getShop(UUID shopId) {
        return shopRepository.findByIdAndDeletedAtIsNull(shopId)
                .orElseThrow(() -> new BusinessException("VENDOR_SHOP_NOT_FOUND", "Shop not found", HttpStatus.NOT_FOUND));
    }

    /**
     * One entry per document type, always in the same order. A type that was never uploaded (or has no
     * file) is listed as NOT_UPLOADED so the admin can see at a glance what is missing.
     */
    private void buildDocuments(UUID shopId, List<VendorKycDocumentDto> out, List<String> missing) {
        Map<VendorDocumentType, VendorDocument> latest = new EnumMap<>(VendorDocumentType.class);
        for (VendorDocument d : documentRepository.findByShopId(shopId)) {
            if (d.getDocumentType() != null) {
                latest.merge(d.getDocumentType(), d, VendorKycServiceImpl::newer);
            }
        }
        for (VendorDocumentType type : VendorDocumentType.values()) {
            VendorDocument d = latest.get(type);
            if (d == null || !hasText(d.getFileUrl())) {
                out.add(VendorKycDocumentDto.builder()
                        .documentType(type.name())
                        .status(NOT_UPLOADED)
                        .uploaded(false)
                        .build());
                missing.add(type.name());
            } else {
                out.add(VendorKycDocumentDto.builder()
                        .documentType(type.name())
                        .status(d.getStatus() == null ? "PENDING" : d.getStatus().name())
                        .fileUrl(d.getFileUrl())
                        .uploaded(true)
                        .uploadedAt(d.getCreatedAt())
                        .lastUpdatedAt(d.getUpdatedAt())
                        .build());
            }
        }
    }

    /** If a vendor somehow has two rows for one type, show the most recently updated one. */
    private static VendorDocument newer(VendorDocument a, VendorDocument b) {
        Instant ua = a.getUpdatedAt();
        Instant ub = b.getUpdatedAt();
        if (ua == null) return b;
        if (ub == null) return a;
        return ub.isAfter(ua) ? b : a;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private VendorKycReviewDto mapToDto(Shop shop) {
        return VendorKycReviewDto.builder()
                .shopId(shop.getId())
                .ownerUserId(shop.getOwnerUserId())
                .businessName(shop.getName())
                .ownerFullName(shop.getFullName())
                .businessPhone(shop.getBusinessPhone())
                .email(shop.getEmail())
                .businessType(shop.getBusinessType())
                .kycStatus(shop.getKycStatus().name())
                .rejectionReason(shop.getKycRejectionReason())
                .submittedAt(shop.getApplicationSubmittedAt())
                .build();
    }
}
