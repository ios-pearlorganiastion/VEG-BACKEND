package com.veggofresh.banner.dto;

import com.veggofresh.banner.entity.BannerActionType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * Create / update body (multipart/form-data, like the catalog screens).
 *
 * <p>On UPDATE this is a full replacement of the text fields: an omitted optional field ({@code subtitle},
 * {@code couponCode}, the action, {@code startsAt}, {@code endsAt}) is cleared. Only {@code image},
 * {@code displayOrder} and {@code enabled} keep their current value when omitted.
 *
 * <p>{@code startsAt} / {@code endsAt}: ISO date-time. Without an offset ("2026-11-01T00:00:00") it is read as Indian
 * time (IST); with an offset or "Z" it is taken as given.
 */
@Getter
@Setter
public class BannerRequestDto {
    private String title;
    private String subtitle;
    /** Required on create; omit on update to keep the current image. jpg / jpeg / png / webp. */
    private MultipartFile image;
    /** A coupon code to show on the banner. Must be an existing coupon. */
    private String couponCode;
    /** What a tap opens. Default NONE. */
    private BannerActionType actionType;
    /** The category / product id for CATEGORY / PRODUCT. */
    private java.util.UUID actionTargetId;
    private String startsAt;
    private String endsAt;
    /** Lower first. Default on create: the bottom. */
    private Integer displayOrder;
    /** Default true on create. */
    private Boolean enabled;
}
