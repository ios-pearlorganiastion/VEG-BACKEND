package com.veggofresh.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank details exactly as the delivery partner entered them. The account number is shown in full because
 * the admin needs it to verify the account; the endpoint is ADMIN-only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryKycBankDto {
    private String bankName;
    private String accountHolderName;
    private String accountNumber;
    private String ifscCode;
    private boolean agreedToPayoutTerms;
    /** true when an account number and IFSC code were entered. */
    private boolean provided;
}
