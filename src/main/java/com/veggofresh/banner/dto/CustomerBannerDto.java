package com.veggofresh.banner.dto;

import com.veggofresh.banner.entity.BannerActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A banner in the customer app. {@code id}, {@code title}, {@code subtitle} and {@code imageUrl} are the fields the
 * app already reads; the rest is new and optional.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBannerDto {
    private UUID id;
    private String title;
    private String subtitle;
    private String imageUrl;
    /** A coupon code to show / copy; the customer applies it in the cart as usual. Null when none. */
    private String couponCode;
    /** NONE, CATEGORY or PRODUCT. */
    private BannerActionType actionType;
    /** The category / product to open when actionType is not NONE. */
    private UUID actionTargetId;
}
