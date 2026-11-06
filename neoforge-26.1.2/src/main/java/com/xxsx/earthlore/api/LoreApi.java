package com.xxsx.earthlore.api;

import com.xxsx.earthlore.condition.Conditions;
import com.xxsx.earthlore.data.CarrierFormRegistry;
import com.xxsx.earthlore.gate.GateService;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.knowledge.LoreSync;
import com.xxsx.earthlore.knowledge.Migrations;
import com.xxsx.earthlore.study.StudyService;
import com.xxsx.earthlore.teach.TeachingService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;

/**
 * The public Java interface of earth_lore (10 册 A-011). Server side only; clients read the synced state for
 * display. Subject mods must not touch the earth_lore:knowledge attachment directly. Machine gates live in
 * {@link LoreMachines}.
 */
public final class LoreApi {
    private LoreApi() {
    }

    /** Under knowledge_scope; root nodes are always known, unloaded nodes never. */
    public static boolean knows(ServerPlayer player, Identifier node) {
        return player != null && KnowledgeService.knows(player.getUUID(), node);
    }

    public static boolean knows(UUID player, Identifier node) {
        return player != null && KnowledgeService.knows(player, node);
    }

    public static boolean knowsAll(ServerPlayer player, Collection<Identifier> nodes) {
        for (Identifier node : nodes) {
            if (!knows(player, node)) {
                return false;
            }
        }
        return true;
    }

    /** The player's own record only, for "already learned" (A-003 rule 4). */
    public static boolean knowsSelf(ServerPlayer player, Identifier node) {
        return player != null && KnowledgeService.knowsSelf(player, node);
    }

    /** ok / soft_capped / locked with the missing gate nodes; content is an item, block, recipe id or ability. */
    public static GateResult check(ServerPlayer player, Object content) {
        return GateService.check(player == null ? null : player.getUUID(), content);
    }

    /** For a machine owner; a null owner knows nothing (A-013 rule 3). */
    public static GateResult check(UUID owner, Object content) {
        return GateService.check(owner, content);
    }

    /** Highest grade allowed (A-010); locked content returns crude, so call check first for hard gates. */
    public static Grade capGrade(ServerPlayer player, Object content, Grade wanted) {
        return cap(check(player, content), wanted);
    }

    public static Grade capGrade(UUID owner, Object content, Grade wanted) {
        return cap(check(owner, content), wanted);
    }

    private static Grade cap(GateResult result, Grade wanted) {
        return result.locked() ? Grade.CRUDE : wanted.min(result.cap());
    }

    /** 0 = categories only, 1 = values (A-010 rule 3). Tools without a readout node always read values. */
    public static int readoutLevel(ServerPlayer player, Item tool) {
        Optional<LoreGraph.Node> node = KnowledgeService.graph().readoutNode(tool);
        return node.isEmpty() || knows(player, node.get().id()) ? 1 : 0;
    }

    /** 0.5 until the owner knows the block's yield node, then 1.0 (A-010 rule 4). */
    public static double yieldFactor(UUID owner, Block block) {
        Optional<LoreGraph.Node> node = KnowledgeService.graph().yieldNode(block);
        if (node.isEmpty()) {
            return 1.0;
        }
        return knows(owner, node.get().id()) ? 1.0 : 0.5;
    }

    /** Machines call this for their products so "made" requirements see items vanilla statistics miss. */
    public static void recordMade(ServerPlayer player, Item item, int count) {
        if (player != null && count > 0) {
            player.awardStat(Stats.ITEM_CRAFTED.get(item), count);
        }
    }

    /**
     * For literature with its own use() (tag earth_lore:custom_study). Items with a use duration must keep the
     * player using them; items without one call this again on every use and the session lives while use repeats.
     */
    public static StudyResult beginStudy(ServerPlayer player, ItemStack literature) {
        InteractionHand hand = player.getItemInHand(InteractionHand.OFF_HAND) == literature
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        double factor = CarrierFormRegistry.studyFactor(literature.getItem());
        return literature.getUseDuration(player) > 0
                ? StudyService.begin(player, literature, factor, hand)
                : StudyService.beginPolled(player, literature, factor, hand);
    }

    /** Commands and migrators only; subject mods must not grant to skip studying. */
    public static boolean grant(ServerPlayer player, Identifier node) {
        return !KnowledgeService.grantAll(player, List.of(node)).isEmpty();
    }

    /** Removes the node and dependent learned nodes; subject mods do not roll back what they granted. */
    public static boolean revoke(ServerPlayer player, Identifier node) {
        boolean changed = !KnowledgeService.revoke(player, node).isEmpty();
        if (changed) {
            LoreSync.sendFull(player);
        }
        return changed;
    }

    public static void registerMigrator(LoreMigrator migrator) {
        Migrations.register(migrator);
    }

    /** Gate nodes of a piece of content, for tooltips and recipe viewers. */
    public static List<Identifier> nodesFor(Object content) {
        return GateService.gatesOf(KnowledgeService.graph(), content).nodes().stream().map(LoreGraph.Node::id).toList();
    }

    /** Value of field, aether or sanctum at a research table (0..100); one provider per key. */
    public static void registerCondition(String key, ConditionProvider provider) {
        Conditions.register(key, provider);
    }

    /** Teaching offers for a subject mod's own merchant entity (A-014 rule 5). */
    public static List<MerchantOffer> teachingOffers(ServerLevel level, EntityType<?> type) {
        return TeachingService.entityOffers(type);
    }

    public static void registerGradeAccess(GradeAccess access) {
        GateService.registerGradeAccess(access);
    }
}
