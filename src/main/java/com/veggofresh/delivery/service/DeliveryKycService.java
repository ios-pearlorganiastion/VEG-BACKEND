package com.veggofresh.delivery.service;

import com.veggofresh.delivery.dto.DeliveryKycDetailDto;
import com.veggofresh.delivery.dto.DeliveryKycReviewDto;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface DeliveryKycService {

    /** Submitted applications waiting for review, oldest first. Drafts are never in here. */
    Page<DeliveryKycReviewDto> listPendingKyc(Pageable pageable);

    /** Full application plus its history. Works for any rider (including a draft), so the admin can look. */
    DeliveryKycDetailDto getKycDetail(UUID userId);

    /** Only an application that is UNDER_REVIEW can be approved; anything else is a 409 {@code KYC_NOT_UNDER_REVIEW}. */
    void approveKyc(UUID adminUserId, UUID userId);

    /**
     * Only an application that is UNDER_REVIEW can be rejected. The reason is required (and shown to the rider);
     * {@code sections} optionally says which parts to fix so the app can highlight just those.
     */
    void rejectKyc(UUID adminUserId, UUID userId, String reason, List<DeliveryKycSection> sections);
}
