package com.xxsx.earthlore.core;

import java.util.Locale;
import java.util.Optional;

/** Gate kinds of a knowledge node (10 册 A-002, A-010). */
public enum Gate {
    ROOT, THEORY, SOFT, HARD, CAP;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Gate> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (Gate gate : values()) {
            if (gate.id().equals(raw)) {
                return Optional.of(gate);
            }
        }
        return Optional.empty();
    }
}

