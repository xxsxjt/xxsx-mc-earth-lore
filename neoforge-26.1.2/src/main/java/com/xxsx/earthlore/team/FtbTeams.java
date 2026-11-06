package com.xxsx.earthlore.team;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.neoforge.FTBTeamsEvent;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * FTB Teams bridge. Loaded only by reflection after ModList confirms ftbteams is present, so no FTB
 * class is touched when the mod is absent. A player outside any party has a personal team of one,
 * which behaves like PLAYER scope.
 */
public final class FtbTeams implements TeamSource {
    public FtbTeams() {
        NeoForge.EVENT_BUS.addListener(FTBTeamsEvent.PlayerChangedTeam.class, event -> TeamTracker.markChanged());
        NeoForge.EVENT_BUS.addListener(FTBTeamsEvent.TeamDeleted.class, event -> TeamTracker.markChanged());
        NeoForge.EVENT_BUS.addListener(FTBTeamsEvent.PlayerJoinedPartyTeam.class, event -> TeamTracker.markChanged());
        NeoForge.EVENT_BUS.addListener(FTBTeamsEvent.PlayerLeftPartyTeam.class, event -> TeamTracker.markChanged());
    }

    @Override
    public String id() {
        return "ftb_teams";
    }

    @Override
    public Set<UUID> members(MinecraftServer server, UUID player) {
        Set<UUID> out = new LinkedHashSet<>();
        out.add(player);
        team(player).ifPresent(team -> out.addAll(team.getMembers()));
        return out;
    }

    @Override
    public String teamName(MinecraftServer server, UUID player) {
        return team(player).filter(team -> !team.isPlayerTeam()).map(Team::getShortName).orElse("");
    }

    private static Optional<Team> team(UUID player) {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        if (!api.isManagerLoaded()) {
            return Optional.empty();
        }
        return api.getManager().getTeamForPlayerID(player);
    }
}

