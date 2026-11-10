package com.veggofresh.delivery.service;

import com.veggofresh.delivery.dto.AdminDeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.DeliveryChangeOverviewDto;
import com.veggofresh.delivery.dto.DeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.entity.DeliveryChangeRequestStatus;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Reviewed changes to verified details after a rider is approved.
 *
 * <p>The rule: <b>the rider keeps working on the old verified details while a change is reviewed.</b> A change is
 * stored as a PENDING request and only reaches the profile when an admin approves it. Sections are those of
 * {@link DeliveryKycSection}: BASIC_INFO (name, city), LICENSE, VEHICLE (type, plate, model, year + insurance) and
 * BANK (bank details + proof). A rider has at most one open request per section.
 *
 * <p>Before approval (draft or rejected) the rider edits the application directly instead; under review it is locked.
 */
public interface DeliveryChangeRequestService {

    // ------------------------------------------------------------------ rider

    /** The rider's verified details with each section's state, open request and last refusal. */
    DeliveryChangeOverviewDto getOverview(UUID userId);

    /** All of the rider's requests, newest first. */
    List<DeliveryChangeRequestDto> getHistory(UUID userId);

    DeliveryChangeRequestDto submitBasicInfo(UUID userId, BasicInfoRequestDto request);

    /** License number plus a new license photo (always required: it is the evidence for the new value). */
    DeliveryChangeRequestDto submitLicense(UUID userId, String licenseNumber, MultipartFile photo);

    /** Vehicle details plus the insurance document (always required). */
    DeliveryChangeRequestDto submitVehicle(UUID userId, String vehicleType, String plateNumber, String vehicleModel,
                                           Integer manufactureYear, MultipartFile insurance);

    /** Bank details plus the bank proof (always required). */
    DeliveryChangeRequestDto submitBank(UUID userId, VerificationStep3RequestDto bank, MultipartFile proof);

    /** Takes back an open request before an admin decides. */
    DeliveryChangeRequestDto cancel(UUID userId, UUID requestId);

    // ------------------------------------------------------------------ admin

    /**
     * Requests across riders. With no filters at all this is the review queue: PENDING, oldest first. With a
     * {@code userId} and no status it is that rider's full history.
     */
    Page<AdminDeliveryChangeRequestDto> list(DeliveryChangeRequestStatus status, DeliveryKycSection section,
                                             UUID userId, Pageable pageable);

    AdminDeliveryChangeRequestDto getDetail(UUID requestId);

    /** Applies the new values to the profile. A BANK change is refused while the rider has payouts waiting for approval. */
    void approve(UUID adminUserId, UUID requestId);

    /** The reason is required and is shown to the rider. The live details stay as they were. */
    void reject(UUID adminUserId, UUID requestId, String reason);
}
