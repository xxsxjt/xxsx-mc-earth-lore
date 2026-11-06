package com.xxsx.earthlore.knowledge;

import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.network.LorePayloads;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** lore_sync_full / lore_sync_delta (10 册 A-017): tracks what each client last received. */
public final class LoreSync {
    private static final int DELTA_LIMIT = 64;
    private static final Map<UUID, Set<Identifier>> sentPersonal = new HashMap<>();
    private static final Map<UUID, Set<Identifier>> sentEffective = new HashMap<>();

    private LoreSync() {
    }

    private static Set<Identifier> personalLoaded(ServerPlayer player) {
        LoreGraph graph = KnowledgeService.graph();
        Set<Identifier> out = new HashSet<>();
        for (Identifier id : KnowledgeService.data(player).learned()) {
            if (graph.loaded(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public static void sendFull(ServerPlayer player) {
        LoreGraph graph = KnowledgeService.graph();
        Set<Identifier> personal = personalLoaded(player);
        Set<Identifier> effective = new HashSet<>(KnowledgeService.effective(player.getUUID()));
        PacketDistributor.sendToPlayer(player, new LorePayloads.SyncFull(graph.hash(),
                bits(graph, graph.rejected().keySet()), bits(graph, personal), bits(graph, effective)));
        sentPersonal.put(player.getUUID(), personal);
        sentEffective.put(player.getUUID(), effective);
    }

    public static void refresh(ServerPlayer player) {
        Set<Identifier> lastPersonal = sentPersonal.get(player.getUUID());
        Set<Identifier> lastEffective = sentEffective.get(player.getUUID());
        if (lastPersonal == null || lastEffective == null) {
            sendFull(player);
            return;
        }
        Set<Identifier> personal = personalLoaded(player);
        Set<Identifier> effective = new HashSet<>(KnowledgeService.effective(player.getUUID()));
        List<Identifier> pAdd = minus(personal, lastPersonal);
        List<Identifier> pRemove = minus(lastPersonal, personal);
        List<Identifier> eAdd = minus(effective, lastEffective);
        List<Identifier> eRemove = minus(lastEffective, effective);
        if (pAdd.isEmpty() && pRemove.isEmpty() && eAdd.isEmpty() && eRemove.isEmpty()) {
            return;
        }
        int total = pAdd.size() + pRemove.size() + eAdd.size() + eRemove.size();
        if (Math.max(Math.max(pAdd.size(), pRemove.size()), Math.max(eAdd.size(), eRemove.size())) > DELTA_LIMIT
                || total > DELTA_LIMIT * 4) {
            sendFull(player);
            return;
        }
        PacketDistributor.sendToPlayer(player, new LorePayloads.SyncDelta(pAdd, pRemove, eAdd, eRemove));
        sentPersonal.put(player.getUUID(), personal);
        sentEffective.put(player.getUUID(), effective);
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refresh(player);
        }
    }

    public static void fullAll(MinecraftServer server) {
        KnowledgeService.invalidate();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendFull(player);
        }
    }

    public static void forget(UUID id) {
        sentPersonal.remove(id);
        sentEffective.remove(id);
    }

    public static void clear() {
        sentPersonal.clear();
        sentEffective.clear();
    }

    private static List<Identifier> minus(Set<Identifier> a, Set<Identifier> b) {
        List<Identifier> out = new ArrayList<>();
        for (Identifier id : a) {
            if (!b.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public static long[] bits(LoreGraph graph, Collection<Identifier> ids) {
        BitSet set = new BitSet(graph.order().size());
        for (Identifier id : ids) {
            int index = graph.index(id);
            if (index >= 0) {
                set.set(index);
            }
        }
        return set.toLongArray();
    }
}

