package com.veggofresh.delivery.service;

import com.veggofresh.delivery.entity.DeliveryApplicationState;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.platform.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * The one rule for "may this rider change their application details right now?".
 *
 * <ul>
 *   <li>DRAFT / REJECTED: yes.</li>
 *   <li>UNDER_REVIEW: no. The admin must never review details that are moving. The rider can withdraw first.</li>
 *   <li>APPROVED: no. Verified details only change through a reviewed change request
 *       ({@code DeliveryChangeRequestService}).</li>
 * </ul>
 *
 * Used by every path that edits something an admin verified: the onboarding steps, the document vault and the
 * account/profile edits for name and vehicle type.
 */
public final class DeliveryApplicationGuard {

    private DeliveryApplicationGuard() {
    }

    /**
     * @param what what the rider is trying to change, used in the message ("name", "license", "documents"...)
     */
    public static void requireEditable(DeliveryPartnerProfile profile, String what) {
        DeliveryApplicationState state = DeliveryApplicationState.of(profile);
        if (state.isEditable()) {
            return;
        }
        if (state == DeliveryApplicationState.UNDER_REVIEW) {
            throw new BusinessException("ONBOARDING_LOCKED_UNDER_REVIEW",
                    "Your application is under review, so your details are locked. "
                            + "Withdraw the application first if you need to change something.",
                    HttpStatus.CONFLICT);
        }
        throw new BusinessException("ONBOARDING_LOCKED_APPROVED",
                "Your " + what + " is verified and locked. To change it, send a change request from your profile: "
                        + "your current details stay active until it is approved.",
                HttpStatus.CONFLICT);
    }
}
