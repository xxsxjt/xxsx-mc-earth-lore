package com.xxsx.earthlore.api;

import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * Result of LoreApi.check: whether content may be made, the highest grade it may reach, and the gate
 * nodes the deciding player (or machine owner) still lacks.
 */
public record GateResult(Status status, Grade cap, List<Identifier> missing) {
    public enum Status { OK, SOFT_CAPPED, LOCKED }

    public static final GateResult FREE = new GateResult(Status.OK, Grade.HEIRLOOM, List.of());

    public GateResult {
        missing = List.copyOf(missing);
    }

    public boolean locked() {
        return status == Status.LOCKED;
    }
}

