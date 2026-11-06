package com.xxsx.earthlore.api;

import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Converts a subject mod's old progress keys into knowledge nodes (10 册 A-015). Called once per player
 * on login; must be read-only toward the old keys and may return nodes the player already knows.
 */
public interface LoreMigrator {
    /** For example "xuanhuan_earth:arcana_v1". */
    String id();

    Set<Identifier> nodesFor(ServerPlayer player);
}

