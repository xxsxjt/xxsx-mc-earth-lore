package com.xxsx.earthlore.team;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.core.FailCodes;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/** Resolves the configured team source and reports membership changes. */
public final class TeamTracker {
    private static final TeamSource NONE = new TeamSource() {
        @Override
        public String id() {
            return "none";
        }

        @Override
        public Set<UUID> members(MinecraftServer server, UUID player) {
            return Set.of(player);
        }

        @Override
        public String teamName(MinecraftServer server, UUID player) {
            return "";
        }
    };

    private static TeamSource source = NONE;
    private static ScoreboardTeams scoreboard;
    private static TeamSource ftb;
    private static volatile boolean changed;
    private static LoreConfig.TeamSourceOption resolvedFor;

    private TeamTracker() {
    }

    public static void markChanged() {
        changed = true;
    }

    /** Chooses the source from team_source; called at server start and when the option changes. */
    public static void resolve() {
        LoreConfig.TeamSourceOption option = LoreConfig.teamSource();
        resolvedFor = option;
        boolean ftbLoaded = ModList.get() != null && ModList.get().isLoaded("ftbteams");
        source = switch (option) {
            case SCOREBOARD -> scoreboard();
            case FTB_TEAMS -> {
                if (ftbLoaded) {
                    yield ftb();
                }
                EarthLore.LOGGER.error("[earth_lore] {} team_source=ftb_teams but FTB Teams is not installed; using player scope",
                        FailCodes.TEAM_SOURCE_MISSING);
                yield NONE;
            }
            case AUTO -> ftbLoaded ? ftb() : scoreboard();
        };
        changed = true;
    }

    public static TeamSource source() {
        if (resolvedFor != LoreConfig.teamSource()) {
            resolve();
        }
        return source;
    }

    public static Set<UUID> members(MinecraftServer server, UUID player) {
        if (LoreConfig.scope() != LoreConfig.Scope.TEAM) {
            return Set.of(player);
        }
        return source().members(server, player);
    }

    /** Polls the scoreboard every 20 ticks; returns true once per detected change. */
    public static boolean tick(MinecraftServer server) {
        TeamSource current = source();
        if (current == scoreboard && server.getTickCount() % 20 == 0 && scoreboard.poll(server)) {
            changed = true;
        }
        if (changed) {
            changed = false;
            return true;
        }
        return false;
    }

    private static TeamSource scoreboard() {
        if (scoreboard == null) {
            scoreboard = new ScoreboardTeams();
        }
        return scoreboard;
    }

    private static TeamSource ftb() {
        if (ftb == null) {
            try {
                ftb = (TeamSource) Class.forName("com.xxsx.earthlore.team.FtbTeams").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                EarthLore.LOGGER.error("[earth_lore] FTB Teams bridge failed to load; using scoreboard teams", e);
                return scoreboard();
            }
        }
        return ftb;
    }
}

