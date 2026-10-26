package com.veggofresh.customer.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /**
     * What the vendor is paid per unit for this line, snapshotted at checkout together with price. NULL on
     * orders placed before vendor prices existed, which means "use price" (the old behaviour).
     */
    @Column(name = "vendor_unit_price", precision = 10, scale = 2)
    private BigDecimal vendorUnitPrice;

    /**
     * Snapshotted at checkout time, same moment `price` already is (see
     * OrderServiceImpl.buildOrderFromCart()) -- NOT a live lookup. An order
     * from months ago must always show the pack size exactly as it was when
     * purchased, even if Admin has since changed or renamed that product.
     */
    @Column(length = 100)
    private String unit;
}
