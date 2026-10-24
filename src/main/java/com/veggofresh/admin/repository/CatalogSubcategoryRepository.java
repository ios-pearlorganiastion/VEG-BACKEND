package com.veggofresh.admin.repository;

import com.veggofresh.admin.entity.CatalogSubcategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CatalogSubcategoryRepository extends JpaRepository<CatalogSubcategory, UUID> {

    List<CatalogSubcategory> findAllByCategoryId(UUID categoryId, Sort sort);

    /** Active-only subcategories of one category -- used by the customer/vendor trees. */
    List<CatalogSubcategory> findAllByCategoryIdAndIsActiveTrue(UUID categoryId, Sort sort);

    boolean existsByNameIgnoreCaseAndCategoryId(String name, UUID categoryId);

    /**
     * Paginated, searchable, active-only subcategories scoped to one category --
     * mirrors CatalogCategoryRepository.searchActive. Same explicit CAST fix on
     * :search to avoid the null-bind-parameter `bytea` issue in Postgres.
     */
    @Query("SELECT s FROM CatalogSubcategory s WHERE " +
           "s.category.id = :categoryId AND " +
           "s.isActive = true AND " +
           "(:search IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<CatalogSubcategory> searchActiveByCategory(@Param("categoryId") UUID categoryId,
                                                     @Param("search") String search,
                                                     Pageable pageable);

    /**
     * Admin paginated search over ALL subcategories of one category, optionally narrowed
     * by status. See CatalogCategoryRepository.searchAdmin for why status is two flags.
     */
    @Query("SELECT s FROM CatalogSubcategory s WHERE " +
           "s.category.id = :categoryId AND " +
           "(:filterActive = false OR s.isActive = :active) AND " +
           "(:search IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<CatalogSubcategory> searchAdminByCategory(@Param("categoryId") UUID categoryId,
                                                   @Param("search") String search,
                                                   @Param("filterActive") boolean filterActive,
                                                   @Param("active") boolean active,
                                                   Pageable pageable);

    /** Every subcategory of one category (active and inactive) as (id, stored position), in display order. */
    @Query("SELECT s.id AS id, s.displayOrder AS displayOrder FROM CatalogSubcategory s " +
           "WHERE s.category.id = :categoryId " +
           "ORDER BY s.displayOrder ASC, s.name ASC, s.id ASC")
    List<CatalogOrderRow> findOrderRowsByCategoryId(@Param("categoryId") UUID categoryId);

    /** Highest stored position inside one category, 0 when it has none. New subcategories get this + 1. */
    @Query("SELECT COALESCE(MAX(s.displayOrder), 0) FROM CatalogSubcategory s WHERE s.category.id = :categoryId")
    Integer findMaxDisplayOrderByCategoryId(@Param("categoryId") UUID categoryId);
}
