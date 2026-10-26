package com.veggofresh.banner.entity;

/** What tapping a banner opens in the customer app. */
public enum BannerActionType {
    /** Nothing (an announcement, or a banner that only advertises a coupon code). */
    NONE,
    /** Opens the catalog category whose id is the banner's action target. */
    CATEGORY,
    /** Opens the catalog product whose id is the banner's action target. */
    PRODUCT
}
