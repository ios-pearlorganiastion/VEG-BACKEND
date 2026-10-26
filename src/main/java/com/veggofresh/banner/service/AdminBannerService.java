package com.veggofresh.banner.service;

import com.veggofresh.banner.dto.AdminBannerDto;
import com.veggofresh.banner.dto.BannerRequestDto;
import org.springframework.data.domain.Page;

import java.util.UUID;

/** Admin banner management. Every method throws a BusinessException with a readable message on bad input. */
public interface AdminBannerService {

    /** The image is required. The new banner goes to the bottom unless a display order is given. */
    AdminBannerDto create(BannerRequestDto request);

    /** Full replacement of the text fields; the image, display order and switch keep their value when omitted. */
    AdminBannerDto update(UUID id, BannerRequestDto request);

    AdminBannerDto get(UUID id);

    /**
     * @param status LIVE, SCHEDULED, EXPIRED or DISABLED (null/blank = all)
     * @param search part of the title or subtitle (null/blank = all)
     */
    Page<AdminBannerDto> list(String status, String search, int page, int size);

    /** The on/off switch. */
    AdminBannerDto setEnabled(UUID id, boolean enabled);

    /** Removes the banner from every list and deletes its image from Cloudinary. */
    void delete(UUID id);
}
