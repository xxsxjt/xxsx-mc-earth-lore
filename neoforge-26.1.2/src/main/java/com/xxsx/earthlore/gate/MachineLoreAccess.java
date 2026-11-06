package com.xxsx.earthlore.gate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Package bridge so the public LoreMachines API can mutate MachineLore without exposing setters. */
public final class MachineLoreAccess {
    private MachineLoreAccess() {
    }

    public static void setOwner(MachineLore lore, UUID owner) {
        lore.setOwner(owner);
    }

    public static boolean remember(MachineLore lore, List<Identifier> relevant, Set<Identifier> known, long tick) {
        return lore.remember(relevant, known, tick);
    }

    public static boolean setFault(MachineLore lore, String code, List<Identifier> nodes) {
        return lore.setFault(code, nodes);
    }
}

