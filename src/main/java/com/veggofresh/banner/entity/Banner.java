package com.veggofresh.banner.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.UUID;

/** One hero banner of the customer app. */
@Entity
@Table(name = "banners")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class Banner extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 255)
    private String subtitle;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /** Cloudinary public_id of the image; needed to delete it when the banner is deleted or its image replaced. */
    @Column(name = "image_public_id", nullable = false, length = 255)
    private String imagePublicId;

    /** UPPER-CASE code of a coupon this banner advertises, or null. */
    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private BannerActionType actionType = BannerActionType.NONE;

    /** The category / product id for CATEGORY / PRODUCT, else null. */
    @Column(name = "action_target_id")
    private UUID actionTargetId;

    /** UTC. Null = show from now. */
    @Column(name = "starts_at")
    private Instant startsAt;

    /** UTC, exclusive. Null = never ends. */
    @Column(name = "ends_at")
    private Instant endsAt;

    /** Lower is shown first. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean enabled = true;
}
