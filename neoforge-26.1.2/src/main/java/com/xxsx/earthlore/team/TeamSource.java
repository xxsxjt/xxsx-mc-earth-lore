package com.xxsx.earthlore.team;

import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Where TEAM scope reads team membership from (10 册 A-003 rule 5). */
public interface TeamSource {
    /** "ftb_teams", "scoreboard" or "none". */
    String id();

    /** Every member of the player's team, online or not, always including the player. */
    Set<UUID> members(MinecraftServer server, UUID player);

    /** Display name of the player's team, or empty when the player has no team. */
    String teamName(MinecraftServer server, UUID player);
}

