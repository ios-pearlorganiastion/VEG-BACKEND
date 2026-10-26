package com.veggofresh.banner.dto;

import com.veggofresh.banner.entity.BannerActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A banner as the admin sees it, including whether customers can actually see it right now. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminBannerDto {
    private UUID id;
    private String title;
    private String subtitle;
    private String imageUrl;
    private String couponCode;
    private BannerActionType actionType;
    private UUID actionTargetId;
    /** UTC. */
    private Instant startsAt;
    private Instant endsAt;
    /** The same moments in Indian time, "yyyy-MM-dd'T'HH:mm:ss" (convenient for the admin form). */
    private String startsAtIst;
    private String endsAtIst;
    private int displayOrder;
    private boolean enabled;

    /** LIVE, SCHEDULED, EXPIRED or DISABLED (the banner's own switch and schedule). */
    private String status;
    /**
     * Status of the advertised coupon (LIVE, SCHEDULED, EXPIRED, DISABLED, USED_UP), MISSING when it no longer exists,
     * or null when the banner advertises no coupon.
     */
    private String couponStatus;
    /** False when the tap target (category / product) was deleted or switched off; null when the banner has none. */
    private Boolean targetAvailable;
    /**
     * Would customers see it right now? LIVE, and its coupon (if any) LIVE, and its tap target (if any) available.
     * (A coupon restricted to certain customers hides the banner from the others as well.)
     */
    private boolean showing;

    private Instant createdAt;
    private Instant updatedAt;
}
