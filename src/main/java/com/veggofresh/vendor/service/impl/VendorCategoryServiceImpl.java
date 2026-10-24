package com.veggofresh.vendor.service.impl;

import com.veggofresh.admin.dto.response.CategoryResponseDto;
import com.veggofresh.admin.dto.response.SubcategoryResponseDto;
import com.veggofresh.admin.service.CatalogCategoryService;
import com.veggofresh.admin.service.CatalogSubcategoryService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.response.VendorCategoryTreeDto;
import com.veggofresh.vendor.dto.response.VendorListingDto;
import com.veggofresh.vendor.dto.response.VendorSubcategoryTreeDto;
import com.veggofresh.vendor.service.VendorCategoryService;
import com.veggofresh.vendor.service.VendorListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VendorCategoryServiceImpl implements VendorCategoryService {

    /** Overfetch size for tree building -- same pattern/caveat as ProductCatalogServiceImpl. */
    private static final int OVERFETCH_SIZE = 2000;

    private final CatalogCategoryService catalogCategoryService;
    private final CatalogSubcategoryService catalogSubcategoryService;
    private final VendorListingService vendorListingService;

    @Override
    public Page<CategoryResponseDto> browseCategories(String search, Pageable pageable) {
        return catalogCategoryService.searchActiveCategories(search, pageable);
    }

    @Override
    public Page<SubcategoryResponseDto> browseSubcategories(UUID categoryId, String search, Pageable pageable) {
        return catalogSubcategoryService.searchActiveSubcategories(categoryId, search, pageable);
    }

    @Override
    public VendorCategoryTreeDto getCategoryTree(UUID ownerUserId, UUID categoryId) {
        CategoryResponseDto category = catalogCategoryService.getCategoryById(categoryId);
        // Vendors must never see an inactive category (same 404 as a category that does not exist).
        if (!category.isActive()) {
            throw new BusinessException("CATALOG_CATEGORY_NOT_FOUND", "Category not found", HttpStatus.NOT_FOUND);
        }
        return buildCategoryTree(ownerUserId, category);
    }

    @Override
    public List<VendorCategoryTreeDto> getFullCatalogTree(UUID ownerUserId) {
        return catalogCategoryService.listCategories(false).stream()
                .map(category -> buildCategoryTree(ownerUserId, category))
                .collect(Collectors.toList());
    }

    private VendorCategoryTreeDto buildCategoryTree(UUID ownerUserId, CategoryResponseDto category) {
        List<VendorSubcategoryTreeDto> subcategoryTrees = catalogSubcategoryService.listActiveByCategory(category.getId()).stream()
                .map(subcategory -> buildSubcategoryTree(ownerUserId, subcategory))
                .collect(Collectors.toList());

        return VendorCategoryTreeDto.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .imageUrl(category.getImageUrl())
                .subcategories(subcategoryTrees)
                .build();
    }

    private VendorSubcategoryTreeDto buildSubcategoryTree(UUID ownerUserId, SubcategoryResponseDto subcategory) {
        // Reuses the exact same, already-correct (post gap-fix) mapping that powers
        // GET /api/vendor/listings -- no duplicated product-mapping logic here.
        Page<VendorListingDto> products = vendorListingService.browseCatalog(
                ownerUserId, null, subcategory.getCategoryId(), subcategory.getId(), PageRequest.of(0, OVERFETCH_SIZE));

        return VendorSubcategoryTreeDto.builder()
                .id(subcategory.getId())
                .name(subcategory.getName())
                .imageUrl(subcategory.getImageUrl())
                .products(products.getContent())
                .build();
    }
}
