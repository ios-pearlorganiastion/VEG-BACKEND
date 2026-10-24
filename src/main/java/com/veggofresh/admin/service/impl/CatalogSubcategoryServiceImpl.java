package com.veggofresh.admin.service.impl;

import com.veggofresh.admin.dto.request.CatalogMoveRequestDto;
import com.veggofresh.admin.dto.request.SubcategoryRequestDto;
import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.admin.dto.response.SubcategoryResponseDto;
import com.veggofresh.admin.entity.CatalogCategory;
import com.veggofresh.admin.entity.CatalogSubcategory;
import com.veggofresh.admin.repository.CatalogCategoryRepository;
import com.veggofresh.admin.repository.CatalogOrderRow;
import com.veggofresh.admin.repository.CatalogSubcategoryRepository;
import com.veggofresh.admin.service.CatalogSubcategoryService;
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
public class CatalogSubcategoryServiceImpl implements CatalogSubcategoryService {

    private final CatalogSubcategoryRepository subcategoryRepository;
    private final CatalogCategoryRepository categoryRepository;
    private final CloudinaryService cloudinaryService;

    @Override
    public SubcategoryResponseDto createSubcategory(SubcategoryRequestDto request) {
        CatalogCategory category = getCategory(request.getCategoryId());
        if (subcategoryRepository.existsByNameIgnoreCaseAndCategoryId(request.getName(), category.getId())) {
            throw new BusinessException("CATALOG_SUBCATEGORY_DUPLICATE",
                    "A subcategory with this name already exists under this category", HttpStatus.CONFLICT);
        }
        CatalogSubcategory subcategory = new CatalogSubcategory();
        subcategory.setCategory(category);
        subcategory.setName(request.getName());
        // Display order is server-managed: a new subcategory always goes to the bottom of its category.
        subcategory.setDisplayOrder(nextDisplayOrder(category.getId()));
        subcategory.setActive(true);

        // Image is optional at creation too -- omit it and add one later via update.
        if (request.getImage() != null && !request.getImage().isEmpty()) {
            CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                    request.getImage(), "veggofresh/catalog/subcategories");
            subcategory.setImageUrl(upload.url());
            subcategory.setImagePublicId(upload.publicId());
        }

        return toDto(subcategoryRepository.save(subcategory));
    }

    @Override
    public SubcategoryResponseDto updateSubcategory(UUID id, SubcategoryRequestDto request) {
        CatalogSubcategory subcategory = getEntity(id);
        CatalogCategory category = getCategory(request.getCategoryId());

        UUID oldCategoryId = subcategory.getCategory().getId();
        boolean categoryChanged = !oldCategoryId.equals(category.getId());

        boolean nameOrCategoryChanged = !subcategory.getName().equalsIgnoreCase(request.getName())
                || categoryChanged;
        if (nameOrCategoryChanged
                && subcategoryRepository.existsByNameIgnoreCaseAndCategoryId(request.getName(), category.getId())) {
            throw new BusinessException("CATALOG_SUBCATEGORY_DUPLICATE",
                    "A subcategory with this name already exists under this category", HttpStatus.CONFLICT);
        }

        // Computed BEFORE the entity is changed, so the query cannot see this row in its new category.
        Integer positionInNewCategory = categoryChanged ? nextDisplayOrder(category.getId()) : null;

        subcategory.setCategory(category);
        subcategory.setName(request.getName());
        // Display order is NOT touched by a plain edit -- it only changes via moveSubcategory().
        // Exception: moved to a different category -> goes to the bottom of the new category.
        if (positionInNewCategory != null) {
            subcategory.setDisplayOrder(positionInNewCategory);
        }

        // Image: optional, single. Omitting it leaves the current image untouched --
        // this does NOT follow the "always overwrite" behavior of the fields above.
        if (request.getImage() != null && !request.getImage().isEmpty()) {
            CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                    request.getImage(), "veggofresh/catalog/subcategories");
            String oldPublicId = subcategory.getImagePublicId();
            subcategory.setImageUrl(upload.url());
            subcategory.setImagePublicId(upload.publicId());
            cloudinaryService.deleteQuietly(oldPublicId);
        }

        SubcategoryResponseDto result = toDto(subcategoryRepository.save(subcategory));

        if (categoryChanged) {
            // Close the gap the subcategory left behind in its old category.
            renumberCategory(oldCategoryId);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public SubcategoryResponseDto getSubcategoryById(UUID id) {
        return toDto(getEntity(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubcategoryResponseDto> listByCategory(UUID categoryId) {
        return subcategoryRepository.findAllByCategoryId(categoryId, CatalogOrdering.BY_DISPLAY_ORDER)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubcategoryResponseDto> listActiveByCategory(UUID categoryId) {
        return subcategoryRepository.findAllByCategoryIdAndIsActiveTrue(categoryId, CatalogOrdering.BY_DISPLAY_ORDER)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubcategoryResponseDto> searchActiveSubcategories(UUID categoryId, String search, Pageable pageable) {
        String normalizedSearch = normalizeSearch(search);
        // Consistent with product/category search: an unknown-but-valid categoryId
        // returns an empty page (200), not a 404 -- no existence check here.
        return subcategoryRepository.searchActiveByCategory(
                categoryId, normalizedSearch, CatalogOrdering.orderedPage(pageable)).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubcategoryResponseDto> searchSubcategories(UUID categoryId, String search, Boolean active, Pageable pageable) {
        String normalizedSearch = normalizeSearch(search);
        boolean filterActive = active != null;
        return subcategoryRepository.searchAdminByCategory(
                        categoryId, normalizedSearch, filterActive, filterActive && active,
                        CatalogOrdering.orderedPage(pageable))
                .map(this::toDto);
    }

    @Override
    public CatalogMoveResultDto moveSubcategory(UUID id, CatalogMoveRequestDto request) {
        CatalogSubcategory subcategory = getEntity(id); // 404 if it does not exist
        UUID categoryId = subcategory.getCategory().getId();

        // The list being reordered is this subcategory's own category (active and inactive).
        Map<UUID, Integer> stored = storedOrder(categoryId);

        List<UUID> newOrder = CatalogOrdering.move(
                new ArrayList<>(stored.keySet()), id, request.getAfterId(), request.getPosition());
        List<CatalogMoveResultDto.Change> changes = CatalogOrdering.diff(stored, newOrder);

        applyOrderChanges(changes);

        return CatalogMoveResultDto.builder()
                .movedId(id)
                .displayOrder(newOrder.indexOf(id) + 1)
                .changed(changes)
                .build();
    }

    @Override
    public SubcategoryResponseDto setActive(UUID id, boolean active) {
        // Inactive subcategories keep their slot in the order -- only visibility changes.
        CatalogSubcategory subcategory = getEntity(id);
        subcategory.setActive(active);
        return toDto(subcategoryRepository.save(subcategory));
    }

    /** Next free position at the bottom of a category (1 when it is empty). */
    private int nextDisplayOrder(UUID categoryId) {
        Integer max = subcategoryRepository.findMaxDisplayOrderByCategoryId(categoryId);
        return (max == null ? 0 : max) + 1;
    }

    private Map<UUID, Integer> storedOrder(UUID categoryId) {
        Map<UUID, Integer> stored = new LinkedHashMap<>();
        for (CatalogOrderRow row : subcategoryRepository.findOrderRowsByCategoryId(categoryId)) {
            stored.put(row.getId(), row.getDisplayOrder());
        }
        return stored;
    }

    /** Rewrites a category's subcategories as 1..n in their current order (only rows that differ). */
    private void renumberCategory(UUID categoryId) {
        Map<UUID, Integer> stored = storedOrder(categoryId);
        applyOrderChanges(CatalogOrdering.diff(stored, new ArrayList<>(stored.keySet())));
    }

    private void applyOrderChanges(List<CatalogMoveResultDto.Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        Map<UUID, Integer> target = changes.stream()
                .collect(Collectors.toMap(CatalogMoveResultDto.Change::getId, CatalogMoveResultDto.Change::getDisplayOrder));
        List<CatalogSubcategory> entities = subcategoryRepository.findAllById(target.keySet());
        for (CatalogSubcategory entity : entities) {
            entity.setDisplayOrder(target.get(entity.getId()));
        }
        try {
            // Flush here so a concurrent edit (@Version) is reported as a clear 409, not a 500 at commit.
            subcategoryRepository.saveAllAndFlush(entities);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new BusinessException("CATALOG_ORDER_CONFLICT",
                    "The subcategory list was changed by someone else. Reload and try again.", HttpStatus.CONFLICT);
        }
    }

    private static String normalizeSearch(String search) {
        return (search != null && !search.isBlank()) ? search.trim() : null;
    }

    private CatalogSubcategory getEntity(UUID id) {
        return subcategoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException("CATALOG_SUBCATEGORY_NOT_FOUND",
                        "Subcategory not found", HttpStatus.NOT_FOUND));
    }

    private CatalogCategory getCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException("CATALOG_CATEGORY_NOT_FOUND",
                        "Category not found", HttpStatus.NOT_FOUND));
    }

    private SubcategoryResponseDto toDto(CatalogSubcategory s) {
        return SubcategoryResponseDto.builder()
                .id(s.getId())
                .categoryId(s.getCategory().getId())
                .categoryName(s.getCategory().getName())
                .name(s.getName())
                .imageUrl(s.getImageUrl())
                .displayOrder(s.getDisplayOrder())
                .isActive(s.isActive())
                .build();
    }
}
