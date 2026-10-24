package com.veggofresh.admin.service;

import com.veggofresh.admin.dto.request.CatalogMoveRequestDto;
import com.veggofresh.admin.dto.request.SubcategoryRequestDto;
import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.admin.dto.response.SubcategoryResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface CatalogSubcategoryService {
    SubcategoryResponseDto createSubcategory(SubcategoryRequestDto request);
    SubcategoryResponseDto updateSubcategory(UUID id, SubcategoryRequestDto request);
    SubcategoryResponseDto getSubcategoryById(UUID id);

    /** All subcategories of a category, active AND inactive (admin view), in display order. */
    List<SubcategoryResponseDto> listByCategory(UUID categoryId);

    /** Active subcategories of a category only, in display order (customer/vendor trees). */
    List<SubcategoryResponseDto> listActiveByCategory(UUID categoryId);

    SubcategoryResponseDto setActive(UUID id, boolean active);

    /**
     * Paginated, searchable, active-only subcategories scoped to one category (vendor/customer
     * pickers). Always sorted by display order then name -- any sort on {@code pageable} is ignored.
     */
    Page<SubcategoryResponseDto> searchActiveSubcategories(UUID categoryId, String search, Pageable pageable);

    /**
     * Admin paginated search over all subcategories of one category. {@code active}: null =
     * active and inactive, TRUE = active only, FALSE = inactive only. Always sorted by display
     * order then name -- any sort on {@code pageable} is ignored.
     */
    Page<SubcategoryResponseDto> searchSubcategories(UUID categoryId, String search, Boolean active, Pageable pageable);

    /**
     * Moves one subcategory within its own category's full list (active and inactive) and
     * shifts the others as needed, in one transaction. See {@link CatalogMoveRequestDto}.
     */
    CatalogMoveResultDto moveSubcategory(UUID id, CatalogMoveRequestDto request);
}
