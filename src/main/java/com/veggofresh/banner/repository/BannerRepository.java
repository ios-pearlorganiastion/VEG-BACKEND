package com.veggofresh.banner.repository;

import com.veggofresh.banner.entity.Banner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BannerRepository extends JpaRepository<Banner, UUID> {

    /** Switched on and inside their schedule, in the admin's order (newest first among equals). */
    @Query("select b from Banner b where b.enabled = true "
            + "and (b.startsAt is null or b.startsAt <= :now) and (b.endsAt is null or b.endsAt > :now) "
            + "order by b.displayOrder asc, b.createdAt desc")
    List<Banner> findInSchedule(@Param("now") Instant now);

    @Query("select b from Banner b order by b.displayOrder asc, b.createdAt desc")
    List<Banner> findAllInOrder();

    /** Highest display order in use, 0 when there are no banners (a new banner goes to the bottom). */
    @Query("select coalesce(max(b.displayOrder), 0) from Banner b")
    int maxDisplayOrder();

    /**
     * Which of these category ids exist and are active. Native on purpose: the banner module must not import another
     * module's entities.
     */
    @Query(value = "select id from catalog_categories where id in (:ids) and deleted_at is null and is_active = true",
            nativeQuery = true)
    List<UUID> findActiveCategoryIds(@Param("ids") Collection<UUID> ids);

    /** Which of these product ids exist and are active. */
    @Query(value = "select id from catalog_products where id in (:ids) and deleted_at is null and is_active = true",
            nativeQuery = true)
    List<UUID> findActiveProductIds(@Param("ids") Collection<UUID> ids);
}
