package com.veggofresh.admin.service;

import com.veggofresh.admin.dto.request.CatalogMoveRequestDto;
import com.veggofresh.admin.dto.request.CategoryRequestDto;
import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.admin.dto.response.CategoryResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface CatalogCategoryService {
    CategoryResponseDto createCategory(CategoryRequestDto request);
    CategoryResponseDto updateCategory(UUID id, CategoryRequestDto request);
    CategoryResponseDto getCategoryById(UUID id);
    List<CategoryResponseDto> listCategories(boolean includeInactive);
    CategoryResponseDto setActive(UUID id, boolean active);

    /**
     * Paginated, searchable, active-only categories (vendor/customer pickers). Always sorted
     * by display order then name -- any sort on {@code pageable} is ignored.
     */
    Page<CategoryResponseDto> searchActiveCategories(String search, Pageable pageable);

    /**
     * Admin paginated search over all categories. {@code active}: null = active and inactive,
     * TRUE = active only, FALSE = inactive only. Always sorted by display order then name --
     * any sort on {@code pageable} is ignored.
     */
    Page<CategoryResponseDto> searchCategories(String search, Boolean active, Pageable pageable);

    /**
     * Moves one category within the full category list (active and inactive) and shifts the
     * others as needed, in one transaction. See {@link CatalogMoveRequestDto}.
     */
    CatalogMoveResultDto moveCategory(UUID id, CatalogMoveRequestDto request);
}
