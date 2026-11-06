package com.xxsx.earthlore.client;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.api.GateResult;
import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.core.Reachability;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.gate.GateService;
import com.xxsx.earthlore.graph.GraphBuilder;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.network.LorePayloads;
import com.xxsx.earthlore.registry.LoreContent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client copy of the knowledge state (10 册 A-017): the graph is rebuilt from the synced data-pack registries plus
 * the server's rejection list; personal and effective sets follow lore_sync_full and lore_sync_delta. Before the
 * first full sync the graph is built from the registries alone so recipe viewers can index gates.
 */
public final class ClientKnowledge {
    private static LoreGraph graph = LoreGraph.EMPTY;
    private static boolean synced;
    private static boolean requested;
    private static Set<Identifier> personal = Set.of();
    private static Set<Identifier> effective = Set.of();
    private static int version;
    private static Identifier studyNode;
    private static int studyTotal;
    private static int studyTicks;

    private ClientKnowledge() {
    }

    public static LoreGraph graph() {
        if (graph == LoreGraph.EMPTY) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                graph = GraphBuilder.client(level.registryAccess(), Set.of());
                version++;
            }
        }
        return graph;
    }

    public static boolean synced() {
        return synced;
    }

    /** Changes whenever the graph or the known sets change; screens use it to refresh caches. */
    public static int version() {
        return version;
    }

    static void onFull(LorePayloads.SyncFull payload, IPayloadContext context) {
        RegistryAccess access = context.player().level().registryAccess();
        List<Identifier> order = new ArrayList<>(GraphBuilder.rawNodes(access).keySet());
        order.sort(Comparator.comparing(Identifier::toString));
        if (order.hashCode() != payload.hash()) {
            EarthLore.LOGGER.warn("[earth_lore] node order differs from the server (client {}, server {})",
                    order.hashCode(), payload.hash());
            if (!requested) {
                requested = true;
                ClientPacketDistributor.sendToServer(new LorePayloads.SyncRequest(order.hashCode()));
            }
        } else {
            requested = false;
        }
        graph = GraphBuilder.client(access, decode(order, payload.rejected()));
        personal = decode(order, payload.personal());
        effective = decode(order, payload.effective());
        synced = true;
        version++;
    }

    static void onDelta(LorePayloads.SyncDelta payload, IPayloadContext context) {
        Set<Identifier> nextPersonal = new HashSet<>(personal);
        nextPersonal.addAll(payload.personalAdd());
        payload.personalRemove().forEach(nextPersonal::remove);
        Set<Identifier> nextEffective = new HashSet<>(effective);
        nextEffective.addAll(payload.effectiveAdd());
        payload.effectiveRemove().forEach(nextEffective::remove);
        personal = nextPersonal;
        effective = nextEffective;
        version++;
    }

    static void onStudyState(LorePayloads.StudyState payload, IPayloadContext context) {
        studyNode = payload.node().orElse(null);
        studyTotal = payload.total();
        studyTicks = 0;
    }

    static void tick() {
        if (studyNode != null && studyTicks < studyTotal) {
            studyTicks++;
        }
    }

    static void reset() {
        graph = LoreGraph.EMPTY;
        synced = false;
        requested = false;
        personal = Set.of();
        effective = Set.of();
        studyNode = null;
        version++;
    }

    private static Set<Identifier> decode(List<Identifier> order, long[] bits) {
        BitSet set = BitSet.valueOf(bits);
        Set<Identifier> out = new HashSet<>();
        for (int i = set.nextSetBit(0); i >= 0 && i < order.size(); i = set.nextSetBit(i + 1)) {
            out.add(order.get(i));
        }
        return out;
    }

    /** Under the server's knowledge_scope; roots always count. */
    public static boolean knows(Identifier id) {
        LoreGraph current = graph();
        return current.loaded(id) && (current.roots().contains(id) || effective.contains(id));
    }

    /** The player's own record. */
    public static boolean knowsSelf(Identifier id) {
        LoreGraph current = graph();
        return current.loaded(id) && (current.roots().contains(id) || personal.contains(id));
    }

    /** Learned by a teammate or another player (team/server scope) but not in the player's own record. */
    public static boolean fromOthers(Identifier id) {
        return effective.contains(id) && !personal.contains(id) && !graph().roots().contains(id);
    }

    public static boolean prerequisitesMet(LoreGraph.Node node, List<Identifier> missing) {
        boolean ok = Reachability.satisfied(node.def(), raw -> {
            Identifier id = Identifier.tryParse(raw);
            return id != null && knows(id);
        });
        if (!ok && missing != null) {
            for (Prereq prereq : node.def().requires()) {
                boolean met = prereq.members().stream().map(Identifier::tryParse).anyMatch(id -> id != null && knows(id));
                if (!met) {
                    prereq.members().stream().map(Identifier::tryParse)
                            .filter(id -> id != null && graph().loaded(id) && !missing.contains(id))
                            .forEach(missing::add);
                }
            }
        }
        return ok;
    }

    /** Local guess for LiteratureItem.use; the server still decides. */
    public static boolean canStudy(Player player, ItemStack stack) {
        if (!synced) {
            return true;
        }
        LoreText text = stack.get(LoreContent.LORE_TEXT.get());
        if (text == null) {
            return false;
        }
        LoreGraph current = graph();
        for (Identifier id : text.nodes()) {
            Optional<LoreGraph.Node> node = current.node(id);
            if (node.isPresent() && !knowsSelf(id) && prerequisitesMet(node.get(), null)) {
                return true;
            }
        }
        return false;
    }

    public static LoreGraph.Gates gates(Object content) {
        return GateService.gatesOf(graph(), content);
    }

    public static GateResult check(LoreGraph.Gates gates) {
        return GateService.evaluate(graph(), gates, ClientKnowledge::knows);
    }

    public static int color(Identifier discipline, int fallback) {
        return graph().discipline(discipline).map(Discipline::color).orElse(fallback);
    }

    public static Identifier studyNode() {
        return studyNode;
    }

    public static int studyTotal() {
        return studyTotal;
    }

    public static int studyTicks() {
        return studyTicks;
    }
}
