package com.veggofresh.banner.controller;

import com.veggofresh.banner.dto.AdminBannerDto;
import com.veggofresh.banner.dto.BannerRequestDto;
import com.veggofresh.banner.service.AdminBannerService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin banner management. Admin only.
 *
 * <pre>
 * POST   /api/admin/banners                multipart/form-data (title, subtitle?, image, couponCode?, actionType?,
 *                                          actionTargetId?, startsAt?, endsAt?, displayOrder?, enabled?)
 * GET    /api/admin/banners?status=&search=&page=&size=     status = LIVE | SCHEDULED | EXPIRED | DISABLED
 * GET    /api/admin/banners/{id}
 * PUT    /api/admin/banners/{id}           multipart/form-data (image optional -- omit to keep the current one)
 * PATCH  /api/admin/banners/{id}/status?enabled=true|false
 * DELETE /api/admin/banners/{id}
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/banners")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminBannerController {

    private final AdminBannerService bannerService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AdminBannerDto>> create(@ModelAttribute BannerRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(bannerService.create(request), "Banner created successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminBannerDto>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(bannerService.list(status, search, page, size)), "Banners retrieved successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminBannerDto>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(bannerService.get(id), "Banner retrieved successfully"));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AdminBannerDto>> update(@PathVariable UUID id,
                                                              @ModelAttribute BannerRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(bannerService.update(id, request), "Banner updated successfully"));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AdminBannerDto>> setStatus(@PathVariable UUID id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponse.success(bannerService.setEnabled(id, enabled),
                enabled ? "Banner switched on" : "Banner switched off"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        bannerService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Banner deleted successfully"));
    }
}
