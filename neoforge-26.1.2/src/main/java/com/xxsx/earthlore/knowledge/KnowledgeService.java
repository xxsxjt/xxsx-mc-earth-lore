package com.xxsx.earthlore.knowledge;

import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.api.LoreLearnedEvent;
import com.xxsx.earthlore.api.LoreRevokedEvent;
import com.xxsx.earthlore.core.NodeDef;
import com.xxsx.earthlore.core.Reachability;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.KnowledgeNode;
import com.xxsx.earthlore.graph.GraphBuilder;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.team.TeamTracker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Server-side knowledge state: the loaded graph, personal records, scope union (10 册 A-003 rules 4–7)
 * and the only code path that writes the knowledge attachment.
 */
public final class KnowledgeService {
    private static MinecraftServer server;
    private static GraphBuilder.Report report;
    private static LoreGraph graph = LoreGraph.EMPTY;
    private static final Map<UUID, Set<Identifier>> effectiveCache = new HashMap<>();

    private KnowledgeService() {
    }

    public static void start(MinecraftServer started) {
        server = started;
        report = GraphBuilder.server(started);
        graph = report.graph();
        TeamTracker.resolve();
        invalidate();
    }

    public static void stop() {
        server = null;
        report = null;
        graph = LoreGraph.EMPTY;
        invalidate();
    }

    public static MinecraftServer server() {
        return server;
    }

    public static boolean running() {
        return server != null;
    }

    public static LoreGraph graph() {
        return graph;
    }

    public static Optional<GraphBuilder.Report> report() {
        return Optional.ofNullable(report);
    }

    /** Re-runs validation and reachability on the current registries (/lore validate). */
    public static GraphBuilder.Report revalidate() {
        report = GraphBuilder.server(server);
        graph = report.graph();
        invalidate();
        return report;
    }

    /**
     * GameTest fixture: replaces the loaded graph with nodes built in code. Disciplines still come from the data
     * pack, nodes skip validation, and revalidate() restores the data-pack graph.
     */
    public static void installForTest(Map<Identifier, KnowledgeNode> nodes) {
        if (server == null) {
            throw new IllegalStateException("earth_lore: no server for the test fixture");
        }
        Map<Identifier, Discipline> disciplines = GraphBuilder.disciplines(server.registryAccess());
        Map<Identifier, NodeDef> defs = new HashMap<>();
        nodes.forEach((id, node) -> defs.put(id, GraphBuilder.toDef(id, node, disciplines, false)));
        graph = LoreGraph.build(disciplines, new TreeMap<>(nodes), defs, Map.of());
        invalidate();
    }

    public static PlayerKnowledge data(ServerPlayer player) {
        return player.getData(LoreContent.KNOWLEDGE.get());
    }

    public static void invalidate() {
        effectiveCache.clear();
    }

    /** Learned nodes of one player, online (attachment) or offline (index). Includes unloaded ids. */
    public static Set<Identifier> personal(UUID id) {
        if (server == null) {
            return Set.of();
        }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return data(online).learned();
        }
        return KnowledgeIndex.get(server).nodes(id);
    }

    /** Nodes counted for prerequisites and gates under the current knowledge_scope; includes roots. */
    public static Set<Identifier> effective(UUID id) {
        Set<Identifier> cached = effectiveCache.get(id);
        if (cached != null) {
            return cached;
        }
        Set<Identifier> out = new HashSet<>(graph.roots());
        if (server != null) {
            switch (LoreConfig.scope()) {
                case PLAYER -> addLoaded(out, personal(id));
                case TEAM -> {
                    for (UUID member : TeamTracker.members(server, id)) {
                        addLoaded(out, personal(member));
                    }
                }
                case SERVER -> {
                    KnowledgeIndex index = KnowledgeIndex.get(server);
                    for (UUID player : index.players()) {
                        addLoaded(out, personal(player));
                    }
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        addLoaded(out, data(player).learned());
                    }
                    addLoaded(out, personal(id));
                }
            }
        }
        Set<Identifier> frozen = Set.copyOf(out);
        effectiveCache.put(id, frozen);
        return frozen;
    }

    private static void addLoaded(Set<Identifier> out, Collection<Identifier> from) {
        for (Identifier node : from) {
            if (graph.loaded(node)) {
                out.add(node);
            }
        }
    }

    /** Own record only: decides "already learned" (A-003 rule 4). */
    public static boolean knowsSelf(ServerPlayer player, Identifier node) {
        Optional<LoreGraph.Node> loaded = graph.node(node);
        if (loaded.isEmpty()) {
            return false;
        }
        return graph.roots().contains(node) || data(player).has(node);
    }

    public static boolean knows(UUID player, Identifier node) {
        if (!graph.loaded(node)) {
            return false;
        }
        return graph.roots().contains(node) || effective(player).contains(node);
    }

    /** Prerequisites satisfied under the current scope; missing receives the unmet node ids. */
    public static boolean prerequisitesMet(UUID player, LoreGraph.Node node, List<Identifier> missing) {
        Set<Identifier> known = effective(player);
        boolean ok = Reachability.satisfied(node.def(), id -> {
            Identifier parsed = Identifier.tryParse(id);
            return parsed != null && known.contains(parsed);
        });
        if (!ok && missing != null) {
            for (var prereq : node.def().requires()) {
                boolean met = prereq.members().stream().map(Identifier::tryParse)
                        .anyMatch(id -> id != null && known.contains(id));
                if (prereq instanceof com.xxsx.earthlore.core.Prereq.All all) {
                    Identifier id = Identifier.tryParse(all.node());
                    met = id != null && known.contains(id);
                }
                if (!met) {
                    prereq.members().stream().map(Identifier::tryParse)
                            .filter(id -> id != null && graph.loaded(id))
                            .forEach(missing::add);
                }
            }
        }
        return ok;
    }

    public static boolean learn(ServerPlayer player, Identifier node, LoreLearnedEvent.Source source, ItemStack literature) {
        if (!graph.loaded(node) || graph.roots().contains(node)) {
            return false;
        }
        if (!data(player).add(node, player.level().getGameTime())) {
            return false;
        }
        afterChange(player);
        NeoForge.EVENT_BUS.post(new LoreLearnedEvent(player, node, source, literature.copy()));
        return true;
    }

    /** /lore grant and LoreApi.grant: adds every node, syncs once (full), then posts one event per node. */
    public static List<Identifier> grantAll(ServerPlayer player, Collection<Identifier> nodes) {
        PlayerKnowledge knowledge = data(player);
        List<Identifier> added = new ArrayList<>();
        for (Identifier node : nodes) {
            if (graph.loaded(node) && !graph.roots().contains(node) && knowledge.add(node, player.level().getGameTime())) {
                added.add(node);
            }
        }
        if (added.isEmpty()) {
            return added;
        }
        afterChange(player);
        LoreSync.sendFull(player);
        for (Identifier node : added) {
            NeoForge.EVENT_BUS.post(new LoreLearnedEvent(player, node, LoreLearnedEvent.Source.GRANT, ItemStack.EMPTY));
        }
        return added;
    }

    /** Adds nodes from a migrator; unloaded ids are kept for later (A-003 rule 2). */
    static List<Identifier> addMigrated(ServerPlayer player, Collection<Identifier> nodes, String migrator) {
        PlayerKnowledge knowledge = data(player);
        List<Identifier> added = new ArrayList<>();
        for (Identifier node : nodes) {
            if (!graph.roots().contains(node) && knowledge.add(node, player.level().getGameTime())) {
                added.add(node);
            }
        }
        knowledge.markMigrated(migrator);
        return added;
    }

    static void postMigrated(ServerPlayer player, List<Identifier> added) {
        if (added.isEmpty()) {
            return;
        }
        afterChange(player);
        for (Identifier node : added) {
            NeoForge.EVENT_BUS.post(new LoreLearnedEvent(player, node, LoreLearnedEvent.Source.MIGRATE, ItemStack.EMPTY));
        }
    }

    /** Removes the node and every learned node that depended on it and is no longer satisfied. */
    public static List<Identifier> revoke(ServerPlayer player, Identifier node) {
        PlayerKnowledge knowledge = data(player);
        if (!knowledge.has(node)) {
            return List.of();
        }
        List<Identifier> removed = new ArrayList<>();
        knowledge.remove(node);
        removed.add(node);
        Set<Identifier> closure = new LinkedHashSet<>(graph.dependentsClosure(node));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Identifier dependent : closure) {
                if (!knowledge.has(dependent)) {
                    continue;
                }
                Optional<LoreGraph.Node> loaded = graph.node(dependent);
                if (loaded.isEmpty()) {
                    continue;
                }
                boolean ok = Reachability.satisfied(loaded.get().def(), id -> {
                    Identifier parsed = Identifier.tryParse(id);
                    return parsed != null && graph.loaded(parsed)
                            && (graph.roots().contains(parsed) || knowledge.has(parsed));
                });
                if (!ok) {
                    knowledge.remove(dependent);
                    removed.add(dependent);
                    changed = true;
                }
            }
        }
        afterChange(player);
        for (Identifier id : removed) {
            NeoForge.EVENT_BUS.post(new LoreRevokedEvent(player, id));
        }
        return removed;
    }

    /** Mirrors the attachment into the index, drops caches and resyncs every online player. */
    public static void afterChange(ServerPlayer player) {
        if (server == null) {
            return;
        }
        KnowledgeIndex.get(server).put(player.getUUID(), player.getScoreboardName(), data(player).learned());
        invalidate();
        LoreSync.refreshAll(server);
    }

    public static void onLogin(ServerPlayer player) {
        if (server == null) {
            return;
        }
        KnowledgeIndex.get(server).put(player.getUUID(), player.getScoreboardName(), data(player).learned());
        invalidate();
        Migrations.runOnLogin(player);
        LoreSync.sendFull(player);
        LoreSync.refreshAll(server);
    }
}

