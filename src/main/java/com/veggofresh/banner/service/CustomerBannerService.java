package com.veggofresh.banner.service;

import com.veggofresh.banner.dto.CustomerBannerDto;

import java.util.List;
import java.util.UUID;

public interface CustomerBannerService {

    /**
     * The banners this customer should see now: switched on, inside their schedule, in the admin's order, and not
     * advertising a coupon this customer cannot use or opening a category / product that no longer exists.
     */
    List<CustomerBannerDto> getBanners(UUID userId);
}
