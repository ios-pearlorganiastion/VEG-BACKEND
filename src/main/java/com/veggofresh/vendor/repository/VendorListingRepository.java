package com.veggofresh.vendor.repository;

import com.veggofresh.vendor.entity.VendorListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VendorListingRepository extends JpaRepository<VendorListing, UUID> {

    Optional<VendorListing> findByShopIdAndCatalogProductId(UUID shopId, UUID catalogProductId);

    List<VendorListing> findByShopId(UUID shopId);

    long countByShopIdAndIsListedTrue(UUID shopId);

    /** Batch count of active listings grouped by shop ID. */
    @Query("SELECT vl.shop.id, COUNT(vl) FROM VendorListing vl WHERE vl.shop.id IN :shopIds AND vl.isListed = true GROUP BY vl.shop.id")
    List<Object[]> countGroupedByShopIdIn(@Param("shopIds") Collection<UUID> shopIds);

    /** All shops (via their listing rows) currently listing this catalog product. */
    List<VendorListing> findByCatalogProductIdAndIsListedTrue(UUID catalogProductId);

    /**
     * Paginated listings for /mine, with an optional isListed filter:
     *   null  -> ALL (both listed and unlisted rows -- unlisting no longer
     *            drops an item out of "mine", only DELETE does that)
     *   true  -> LISTED only
     *   false -> UNLISTED only
     * Soft-deleted rows are already excluded by the entity's
     * @Where(clause = "deleted_at IS NULL"), so no extra check is needed here.
     * isListed is a Boolean (not a raw string), so this doesn't hit the same
     * null-bind-parameter issue that :search does in CatalogProductRepository --
     * it's a plain boolean-column comparison, unambiguous to Postgres.
     */
    @Query("SELECT v FROM VendorListing v WHERE v.shop.id = :shopId AND (:isListed IS NULL OR v.isListed = :isListed)")
    Page<VendorListing> findByShopIdAndOptionalListedStatus(@Param("shopId") UUID shopId,
                                                             @Param("isListed") Boolean isListed,
                                                             Pageable pageable);
}
