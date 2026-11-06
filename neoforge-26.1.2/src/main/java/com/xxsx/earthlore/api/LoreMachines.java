package com.xxsx.earthlore.api;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.gate.GateService;
import com.xxsx.earthlore.gate.MachineLore;
import com.xxsx.earthlore.gate.MachineLoreAccess;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeIndex;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.registry.LoreContent;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Machine gates of 10 册 A-013 for any block entity: the owner is recorded on placement, an online owner is
 * checked live (and remembered in the snapshot), an offline owner by the last known knowledge, and an
 * ownerless machine caps soft content at common and refuses hard content.
 */
public final class LoreMachines {
    /** Blocks whose placer is recorded automatically; earth_lore lists the vanilla furnace, blast furnace and smoker. */
    public static final TagKey<Block> RECORDS_OWNER = TagKey.create(Registries.BLOCK, EarthLore.id("records_owner"));

    private LoreMachines() {
    }

    public static Optional<UUID> owner(BlockEntity be) {
        return be.getExistingData(LoreContent.MACHINE.get()).flatMap(MachineLore::owner);
    }

    public static void setOwner(BlockEntity be, ServerPlayer player) {
        MachineLoreAccess.setOwner(be.getData(LoreContent.MACHINE.get()), player == null ? null : player.getUUID());
        be.setChanged();
    }

    /** Sneak + empty hand by the owner or an operator, or anyone on an ownerless machine (A-013 rule 4). */
    public static Optional<String> claim(BlockEntity be, ServerPlayer player) {
        Optional<UUID> current = owner(be);
        boolean op = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (current.isPresent() && !current.get().equals(player.getUUID()) && !op) {
            return Optional.of(FailCodes.NOT_OWNER);
        }
        setOwner(be, player);
        return Optional.empty();
    }

    public static GateResult check(BlockEntity be, Object content) {
        LoreGraph graph = KnowledgeService.graph();
        LoreGraph.Gates gates = GateService.gatesOf(graph, content);
        if (gates.isEmpty()) {
            return GateResult.FREE;
        }
        Optional<UUID> owner = owner(be);
        if (owner.isEmpty() || !KnowledgeService.running()) {
            return GateService.evaluate(graph, gates, id -> false);
        }
        Set<Identifier> known = knownRelevant(be, owner.get(), GateService.relevantNodes(graph, gates));
        return GateService.evaluate(graph, gates, known::contains);
    }

    public static Grade capGrade(BlockEntity be, Object content, Grade wanted) {
        GateResult result = check(be, content);
        return result.locked() ? Grade.CRUDE : wanted.min(result.cap());
    }

    public static double yieldFactor(BlockEntity be, Block block) {
        Optional<LoreGraph.Node> node = KnowledgeService.graph().yieldNode(block);
        if (node.isEmpty()) {
            return 1.0;
        }
        Optional<UUID> owner = owner(be);
        if (owner.isEmpty()) {
            return 0.5;
        }
        return knownRelevant(be, owner.get(), List.of(node.get().id())).contains(node.get().id()) ? 1.0 : 0.5;
    }

    public static void setFault(BlockEntity be, String code, List<Identifier> nodes) {
        MachineLore lore = be.getData(LoreContent.MACHINE.get());
        if (MachineLoreAccess.setFault(lore, code, nodes)) {
            be.setChanged();
            if (be.getLevel() != null && !be.getLevel().isClientSide()) {
                be.syncData(LoreContent.MACHINE.get());
            }
        }
    }

    public static void clearFault(BlockEntity be) {
        MachineLore lore = be.getExistingDataOrNull(LoreContent.MACHINE.get());
        if (lore != null && !lore.fault().isEmpty()) {
            setFault(be, "", List.of());
        }
    }

    private static Set<Identifier> knownRelevant(BlockEntity be, UUID owner, List<Identifier> relevant) {
        MinecraftServer server = KnowledgeService.server();
        MachineLore lore = be.getData(LoreContent.MACHINE.get());
        Set<Identifier> known = new HashSet<>();
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (online == null && !KnowledgeIndex.get(server).players().contains(owner)) {
            for (Identifier id : relevant) {
                if (lore.snapshot().contains(id) || KnowledgeService.graph().roots().contains(id)) {
                    known.add(id);
                }
            }
            return known;
        }
        for (Identifier id : relevant) {
            if (KnowledgeService.knows(owner, id)) {
                known.add(id);
            }
        }
        if (online != null && MachineLoreAccess.remember(lore, relevant, known, server.overworld().getGameTime())) {
            be.setChanged();
        }
        return known;
    }
}

