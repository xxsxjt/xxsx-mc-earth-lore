package com.xxsx.earthlore.team;

import com.xxsx.earthlore.knowledge.KnowledgeIndex;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Vanilla scoreboard teams. Members are stored by name; names resolve through online players first and
 * then the knowledge index, and unknown names are skipped.
 */
public final class ScoreboardTeams implements TeamSource {
    private final Map<UUID, String> lastSeen = new HashMap<>();

    @Override
    public String id() {
        return "scoreboard";
    }

    @Override
    public Set<UUID> members(MinecraftServer server, UUID player) {
        Set<UUID> out = new LinkedHashSet<>();
        out.add(player);
        String name = nameOf(server, player);
        if (name == null) {
            return out;
        }
        PlayerTeam team = server.getScoreboard().getPlayersTeam(name);
        if (team == null) {
            return out;
        }
        KnowledgeIndex index = KnowledgeIndex.get(server);
        for (String member : team.getPlayers()) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(member);
            if (online != null) {
                out.add(online.getUUID());
            } else {
                index.byName(member).ifPresent(out::add);
            }
        }
        return out;
    }

    @Override
    public String teamName(MinecraftServer server, UUID player) {
        String name = nameOf(server, player);
        if (name == null) {
            return "";
        }
        PlayerTeam team = server.getScoreboard().getPlayersTeam(name);
        return team == null ? "" : team.getName();
    }

    /** True when any online player's team changed since the last poll (polled every 20 ticks). */
    boolean poll(MinecraftServer server) {
        boolean changed = false;
        Map<UUID, String> now = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerTeam team = server.getScoreboard().getPlayersTeam(player.getScoreboardName());
            now.put(player.getUUID(), team == null ? "" : team.getName());
        }
        for (Map.Entry<UUID, String> entry : now.entrySet()) {
            if (!Objects.equals(lastSeen.get(entry.getKey()), entry.getValue())) {
                changed = true;
            }
        }
        lastSeen.clear();
        lastSeen.putAll(now);
        return changed;
    }

    private static String nameOf(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getScoreboardName();
        }
        return KnowledgeIndex.get(server).name(player).orElse(null);
    }
}

