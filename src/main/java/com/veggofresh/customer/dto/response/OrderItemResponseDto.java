package com.veggofresh.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemResponseDto {
    private UUID id;
    private UUID productId;
    private String productName;
    private int quantity;
    private BigDecimal price;
    private String unit;
    private BigDecimal subTotal;

    /**
     * What the vendor is paid per unit. @JsonIgnore: it is available to server code (vendor screens, settlement)
     * but is NEVER written into any JSON response, so customers can never see the platform margin.
     */
    @JsonIgnore
    private BigDecimal vendorUnitPrice;

    /** Vendor payout per unit; falls back to the customer price for old orders and never exceeds it. */
    public BigDecimal effectiveVendorUnitPrice() {
        if (price == null || vendorUnitPrice == null) {
            return price;
        }
        return vendorUnitPrice.compareTo(price) > 0 ? price : vendorUnitPrice;
    }

    /** Vendor payout for this line (effective vendor unit price x quantity). */
    public BigDecimal vendorSubTotal() {
        BigDecimal unit = effectiveVendorUnitPrice();
        return unit == null ? BigDecimal.ZERO : unit.multiply(BigDecimal.valueOf(quantity));
    }
}
