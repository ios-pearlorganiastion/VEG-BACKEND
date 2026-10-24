package com.veggofresh.admin.service.impl;

import com.veggofresh.admin.dto.request.CatalogMoveRequestDto;
import com.veggofresh.admin.dto.request.CategoryRequestDto;
import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.admin.dto.response.CategoryResponseDto;
import com.veggofresh.admin.entity.CatalogCategory;
import com.veggofresh.admin.repository.CatalogCategoryRepository;
import com.veggofresh.admin.repository.CatalogOrderRow;
import com.veggofresh.admin.service.CatalogCategoryService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CatalogCategoryServiceImpl implements CatalogCategoryService {

    private final CatalogCategoryRepository categoryRepository;
    private final CloudinaryService cloudinaryService;

    @Override
    public CategoryResponseDto createCategory(CategoryRequestDto request) {
        if (categoryRepository.existsByNameIgnoreCase(request.getName())) {
            throw new BusinessException("CATALOG_CATEGORY_DUPLICATE",
                    "A category with this name already exists", HttpStatus.CONFLICT);
        }
        CatalogCategory category = new CatalogCategory();
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        // Display order is server-managed: a new category always goes to the bottom.
        category.setDisplayOrder(categoryRepository.findMaxDisplayOrder() + 1);
        category.setActive(true);

        // Image is optional at creation too -- omit it and add one later via update.
        if (request.getImage() != null && !request.getImage().isEmpty()) {
            CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                    request.getImage(), "veggofresh/catalog/categories");
            category.setImageUrl(upload.url());
            category.setImagePublicId(upload.publicId());
        }

        return toDto(categoryRepository.save(category));
    }

    @Override
    public CategoryResponseDto updateCategory(UUID id, CategoryRequestDto request) {
        CatalogCategory category = getEntity(id);
        if (!category.getName().equalsIgnoreCase(request.getName())
                && categoryRepository.existsByNameIgnoreCase(request.getName())) {
            throw new BusinessException("CATALOG_CATEGORY_DUPLICATE",
                    "A category with this name already exists", HttpStatus.CONFLICT);
        }
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        // Display order is NOT touched here -- it only changes via moveCategory().

        // Image: optional, single. Omitting it leaves the current image untouched --
        // this does NOT follow the "always overwrite" behavior of the fields above.
        if (request.getImage() != null && !request.getImage().isEmpty()) {
            CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                    request.getImage(), "veggofresh/catalog/categories");
            String oldPublicId = category.getImagePublicId();
            category.setImageUrl(upload.url());
            category.setImagePublicId(upload.publicId());
            cloudinaryService.deleteQuietly(oldPublicId);
        }

        return toDto(categoryRepository.save(category));
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponseDto getCategoryById(UUID id) {
        return toDto(getEntity(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponseDto> listCategories(boolean includeInactive) {
        List<CatalogCategory> categories = includeInactive
                ? categoryRepository.findAll(CatalogOrdering.BY_DISPLAY_ORDER)
                : categoryRepository.findAllByIsActiveTrue(CatalogOrdering.BY_DISPLAY_ORDER);
        return categories.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CategoryResponseDto> searchActiveCategories(String search, Pageable pageable) {
        String normalizedSearch = normalizeSearch(search);
        return categoryRepository.searchActive(normalizedSearch, CatalogOrdering.orderedPage(pageable))
                .map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CategoryResponseDto> searchCategories(String search, Boolean active, Pageable pageable) {
        String normalizedSearch = normalizeSearch(search);
        boolean filterActive = active != null;
        return categoryRepository.searchAdmin(
                        normalizedSearch, filterActive, filterActive && active, CatalogOrdering.orderedPage(pageable))
                .map(this::toDto);
    }

    @Override
    public CatalogMoveResultDto moveCategory(UUID id, CatalogMoveRequestDto request) {
        getEntity(id); // 404 if the category does not exist

        // 1. Light read: just (id, stored position) of the whole list, in current order.
        Map<UUID, Integer> stored = new LinkedHashMap<>();
        for (CatalogOrderRow row : categoryRepository.findAllOrderRows()) {
            stored.put(row.getId(), row.getDisplayOrder());
        }

        // 2. Work out the new order, 3. keep only the rows whose number changes.
        List<UUID> newOrder = CatalogOrdering.move(
                new ArrayList<>(stored.keySet()), id, request.getAfterId(), request.getPosition());
        List<CatalogMoveResultDto.Change> changes = CatalogOrdering.diff(stored, newOrder);

        // 4. Write only those rows, in this one transaction.
        applyOrderChanges(changes);

        return CatalogMoveResultDto.builder()
                .movedId(id)
                .displayOrder(newOrder.indexOf(id) + 1)
                .changed(changes)
                .build();
    }

    @Override
    public CategoryResponseDto setActive(UUID id, boolean active) {
        // Inactive categories keep their slot in the order -- only visibility changes.
        CatalogCategory category = getEntity(id);
        category.setActive(active);
        return toDto(categoryRepository.save(category));
    }

    private void applyOrderChanges(List<CatalogMoveResultDto.Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        Map<UUID, Integer> target = changes.stream()
                .collect(Collectors.toMap(CatalogMoveResultDto.Change::getId, CatalogMoveResultDto.Change::getDisplayOrder));
        List<CatalogCategory> entities = categoryRepository.findAllById(target.keySet());
        for (CatalogCategory entity : entities) {
            entity.setDisplayOrder(target.get(entity.getId()));
        }
        try {
            // Flush here so a concurrent edit (@Version) is reported as a clear 409, not a 500 at commit.
            categoryRepository.saveAllAndFlush(entities);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new BusinessException("CATALOG_ORDER_CONFLICT",
                    "The category list was changed by someone else. Reload and try again.", HttpStatus.CONFLICT);
        }
    }

    private static String normalizeSearch(String search) {
        return (search != null && !search.isBlank()) ? search.trim() : null;
    }

    private CatalogCategory getEntity(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException("CATALOG_CATEGORY_NOT_FOUND",
                        "Category not found", HttpStatus.NOT_FOUND));
    }

    private CategoryResponseDto toDto(CatalogCategory c) {
        return CategoryResponseDto.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .imageUrl(c.getImageUrl())
                .displayOrder(c.getDisplayOrder())
                .isActive(c.isActive())
                .build();
    }
}
