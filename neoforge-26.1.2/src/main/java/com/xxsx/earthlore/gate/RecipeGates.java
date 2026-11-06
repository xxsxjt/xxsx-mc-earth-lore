package com.xxsx.earthlore.gate;

import com.xxsx.earthlore.api.GateResult;
import com.xxsx.earthlore.api.LoreMachines;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.registry.LoreSounds;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

/** A-012 at vanilla stations; called from the mixins. The recipe and its result item are both checked. */
public final class RecipeGates {
    private RecipeGates() {
    }

    /** Crafting grid, smithing table, stonecutter: soft lowers the grade, hard empties the result slot. */
    public static ItemStack forPlayer(ServerPlayer player, RecipeHolder<?> recipe, ItemStack result) {
        if (result.isEmpty() || player == null || !KnowledgeService.running()) {
            return result;
        }
        LoreGraph graph = KnowledgeService.graph();
        LoreGraph.Gates gates = GateService.gatesOf(graph, content(recipe, result));
        if (gates.isEmpty()) {
            return result;
        }
        GateResult gate = GateService.evaluate(graph, gates, id -> KnowledgeService.knows(player.getUUID(), id));
        if (gate.locked()) {
            player.sendOverlayMessage(lockedMessage(graph, gate));
            LoreSounds.locked(player);
            return ItemStack.EMPTY;
        }
        return GateService.apply(result, gate);
    }

    /** Vanilla furnaces: decided by the machine owner; hard sets fault=lore_locked and refuses to cook. */
    public static ItemStack forMachine(BlockEntity machine, RecipeHolder<?> recipe, ItemStack result) {
        if (result.isEmpty() || machine == null || !KnowledgeService.running()) {
            return result;
        }
        GateResult gate = LoreMachines.check(machine, content(recipe, result));
        if (gate.locked()) {
            LoreMachines.setFault(machine, FailCodes.LOCKED, gate.missing());
            return ItemStack.EMPTY;
        }
        LoreMachines.clearFault(machine);
        return GateService.apply(result, gate);
    }

    private static List<Object> content(RecipeHolder<?> recipe, ItemStack result) {
        return recipe == null ? List.of(result) : List.of(recipe, result);
    }

    public static Component lockedMessage(LoreGraph graph, GateResult gate) {
        MutableComponent out = Component.translatable("lore.earth_lore.fail." + FailCodes.LOCKED);
        List<Identifier> missing = gate.missing();
        if (!missing.isEmpty()) {
            out.append(": ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                Identifier id = missing.get(i);
                out.append(graph.node(id).map(node -> (Component) Component.translatable(node.nameKey()))
                        .orElse(Component.literal(id.toString())));
            }
        }
        return out;
    }
}
