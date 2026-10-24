package com.veggofresh.admin.repository;

import com.veggofresh.admin.entity.CatalogCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CatalogCategoryRepository extends JpaRepository<CatalogCategory, UUID> {

    boolean existsByNameIgnoreCase(String name);

    List<CatalogCategory> findAllByIsActiveTrue(Sort sort);

    /**
     * Paginated, searchable, active-only categories -- used by the vendor-facing
     * category picker (VendorCategoryController) so a vendor can type-to-search
     * instead of scrolling a flat dropdown. :search uses an explicit CAST to
     * string so Postgres doesn't fall back to inferring `bytea` for the bind
     * parameter when it's null (see CatalogProductRepository.search for the
     * same fix applied to product search).
     */
    @Query("SELECT c FROM CatalogCategory c WHERE " +
           "c.isActive = true AND " +
           "(:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<CatalogCategory> searchActive(@Param("search") String search, Pageable pageable);

    /**
     * Admin paginated search over ALL categories, optionally narrowed by status.
     * Status uses two never-null flags (instead of one nullable Boolean) so Postgres
     * never has to infer the type of a null bind parameter:
     * filterActive=false -> active and inactive; filterActive=true -> only isActive = :active.
     */
    @Query("SELECT c FROM CatalogCategory c WHERE " +
           "(:filterActive = false OR c.isActive = :active) AND " +
           "(:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<CatalogCategory> searchAdmin(@Param("search") String search,
                                      @Param("filterActive") boolean filterActive,
                                      @Param("active") boolean active,
                                      Pageable pageable);

    /** Every category (active and inactive) as (id, stored position), in display order. Used by move. */
    @Query("SELECT c.id AS id, c.displayOrder AS displayOrder FROM CatalogCategory c " +
           "ORDER BY c.displayOrder ASC, c.name ASC, c.id ASC")
    List<CatalogOrderRow> findAllOrderRows();

    /** Highest stored position, 0 when there are no categories. New categories get this + 1. */
    @Query("SELECT COALESCE(MAX(c.displayOrder), 0) FROM CatalogCategory c")
    Integer findMaxDisplayOrder();
}
