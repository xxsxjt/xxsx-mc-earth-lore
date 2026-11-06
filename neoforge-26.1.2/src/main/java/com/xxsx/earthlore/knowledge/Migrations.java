package com.xxsx.earthlore.knowledge;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.api.LoreMigrator;
import com.xxsx.earthlore.core.MigrationLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Registered LoreMigrators (10 册 A-015). */
public final class Migrations {
    private static final Map<String, LoreMigrator> MIGRATORS = new LinkedHashMap<>();

    private Migrations() {
    }

    public static synchronized void register(LoreMigrator migrator) {
        if (MIGRATORS.putIfAbsent(migrator.id(), migrator) != null) {
            EarthLore.LOGGER.error("[earth_lore] migrator {} registered twice; keeping the first", migrator.id());
        }
    }

    public static synchronized List<String> ids() {
        return List.copyOf(MIGRATORS.keySet());
    }

    public static void runOnLogin(ServerPlayer player) {
        run(player, false);
    }

    /** /lore migrate: ignores the migrated list and runs everything again; still only adds. */
    public static List<Identifier> rerun(ServerPlayer player) {
        return run(player, true);
    }

    private static List<Identifier> run(ServerPlayer player, boolean force) {
        PlayerKnowledge knowledge = KnowledgeService.data(player);
        List<String> installed = ids();
        Set<String> learned = knowledge.learned().stream().map(Identifier::toString).collect(Collectors.toSet());
        Map<String, Set<String>> results = new LinkedHashMap<>();
        MigrationLedger.Outcome outcome = MigrationLedger.run(learned, knowledge.migrated(), installed, id -> {
            Set<String> nodes;
            try {
                nodes = MIGRATORS.get(id).nodesFor(player).stream().map(Identifier::toString)
                        .collect(Collectors.toSet());
            } catch (RuntimeException e) {
                EarthLore.LOGGER.error("[earth_lore] migrator {} failed for {}", id, player.getScoreboardName(), e);
                nodes = Set.of();
            }
            results.put(id, nodes);
            return nodes;
        }, force);
        List<Identifier> added = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : results.entrySet()) {
            List<Identifier> nodes = entry.getValue().stream().map(Identifier::tryParse)
                    .filter(id -> id != null && outcome.added().contains(id.toString())).toList();
            added.addAll(KnowledgeService.addMigrated(player, nodes, entry.getKey()));
        }
        KnowledgeService.postMigrated(player, added);
        return added;
    }
}

