package com.veggofresh.delivery.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The parts of an application an admin can send back, so the rider fixes only what is wrong instead of
 * redoing everything.
 */
public enum DeliveryKycSection {
    BASIC_INFO,
    LICENSE,
    VEHICLE,
    BANK;

    /** Stored as a comma-separated list of names (a handful of values; no separate table needed). */
    public static String toCsv(Collection<DeliveryKycSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        return sections.stream().distinct().map(Enum::name).collect(Collectors.joining(","));
    }

    public static List<DeliveryKycSection> fromCsv(String csv) {
        List<DeliveryKycSection> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                result.add(DeliveryKycSection.valueOf(name));
            }
        }
        return result;
    }
}
