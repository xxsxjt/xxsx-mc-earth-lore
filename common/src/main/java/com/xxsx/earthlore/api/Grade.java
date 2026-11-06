package com.xxsx.earthlore.api;

import java.util.Locale;
import java.util.Optional;

/**
 * The five quality grades. Serialized names match docs/earth-series-plan/catalog/quality.md so subject
 * mods can map them onto their own quality components by name.
 */
public enum Grade {
    CRUDE, COMMON, FINE, MASTERWORK, HEIRLOOM;

    public int tier() {
        return ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Grade min(Grade other) {
        return tier() <= other.tier() ? this : other;
    }

    public static Grade ofTier(int tier) {
        Grade[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, tier))];
    }

    public static Optional<Grade> byId(String id) {
        for (Grade grade : values()) {
            if (grade.id().equals(id)) {
                return Optional.of(grade);
            }
        }
        return Optional.empty();
    }
}

