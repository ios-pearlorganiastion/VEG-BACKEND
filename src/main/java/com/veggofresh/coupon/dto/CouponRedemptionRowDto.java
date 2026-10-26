package com.veggofresh.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One use of a coupon, for the admin's "who used it" list. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponRedemptionRowDto {
    private UUID redemptionId;
    private UUID userId;
    private String phone;
    private BigDecimal discount;
    private Instant usedAt;
    /** True once every order of that checkout was cancelled and the use was given back. */
    private boolean released;
    private Instant releasedAt;
    /** Order numbers of that checkout. */
    private String orderNumbers;
}
