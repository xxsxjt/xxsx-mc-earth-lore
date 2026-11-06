package com.xxsx.earthlore.gate;

import com.xxsx.earthlore.api.GateResult;
import com.xxsx.earthlore.api.Grade;
import com.xxsx.earthlore.api.GradeAccess;
import com.xxsx.earthlore.core.GateMath;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Gate semantics of 10 册 A-010 applied to concrete content. */
public final class GateService {
    private static final List<GradeAccess> GRADE_ACCESS = new CopyOnWriteArrayList<>();

    private GateService() {
    }

    public static void registerGradeAccess(GradeAccess access) {
        GRADE_ACCESS.add(access);
    }

    public static LoreGraph.Gates gatesOf(LoreGraph graph, Object content) {
        if (content == null) {
            return LoreGraph.Gates.NONE;
        }
        if (content instanceof ItemStack stack) {
            return stack.isEmpty() ? LoreGraph.Gates.NONE : graph.itemGates(stack.getItem());
        }
        if (content instanceof Block block) {
            return graph.blockGates(block);
        }
        if (content instanceof BlockState state) {
            return graph.blockGates(state.getBlock());
        }
        if (content instanceof ItemLike like) {
            return graph.itemGates(like.asItem());
        }
        if (content instanceof RecipeHolder<?> holder) {
            Identifier id = holder.id().identifier();
            Identifier type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            LoreGraph.Gates gates = graph.recipeGates(id, type);
            Optional<Identifier> wrapped = GatedRecipes.nodeFor(id);
            if (wrapped.isPresent()) {
                Optional<LoreGraph.Node> node = graph.node(wrapped.get());
                gates = merge(gates, node.map(n -> new LoreGraph.Gates(List.of(n), false))
                        .orElse(new LoreGraph.Gates(List.of(), true)));
            }
            return gates;
        }
        if (content instanceof ResourceKey<?> key) {
            return graph.recipeGates(key.identifier(), null);
        }
        if (content instanceof Identifier id) {
            return graph.recipeGates(id, null);
        }
        if (content instanceof String ability) {
            return graph.abilityGates(ability);
        }
        if (content instanceof Collection<?> many) {
            LoreGraph.Gates out = LoreGraph.Gates.NONE;
            for (Object one : many) {
                out = merge(out, gatesOf(graph, one));
            }
            return out;
        }
        return LoreGraph.Gates.NONE;
    }

    private static LoreGraph.Gates merge(LoreGraph.Gates a, LoreGraph.Gates b) {
        if (b.isEmpty()) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        List<LoreGraph.Node> nodes = new ArrayList<>(a.nodes());
        for (LoreGraph.Node node : b.nodes()) {
            if (!nodes.contains(node)) {
                nodes.add(node);
            }
        }
        return new LoreGraph.Gates(List.copyOf(nodes), a.lockedByRejected() || b.lockedByRejected());
    }

    /** @param knows whether the deciding player, or machine owner, knows a node (scope already applied) */
    public static GateResult evaluate(LoreGraph graph, LoreGraph.Gates gates, Predicate<Identifier> knows) {
        if (gates.isEmpty()) {
            return GateResult.FREE;
        }
        GateMath.Outcome outcome = gates.lockedByRejected() ? GateMath.Outcome.LOCKED_OUT : GateMath.Outcome.FREE;
        List<Identifier> missing = new ArrayList<>();
        for (LoreGraph.Node node : gates.nodes()) {
            boolean known = knows.test(node.id());
            boolean capKnown = graph.capstoneFor(node).map(cap -> knows.test(cap.id())).orElse(false);
            outcome = outcome.and(GateMath.evaluate(node.gate(), known, capKnown));
            if (!known) {
                missing.add(node.id());
            }
        }
        GateResult.Status status = switch (outcome.verdict()) {
            case OK -> GateResult.Status.OK;
            case SOFT_CAPPED -> GateResult.Status.SOFT_CAPPED;
            case LOCKED -> GateResult.Status.LOCKED;
        };
        return new GateResult(status, outcome.cap(), missing);
    }

    public static GateResult check(java.util.UUID player, Object content) {
        LoreGraph graph = KnowledgeService.graph();
        return evaluate(graph, gatesOf(graph, content), id -> player != null && KnowledgeService.knows(player, id));
    }

    /** Nodes relevant to a content check: gate nodes plus their capstones. */
    public static List<Identifier> relevantNodes(LoreGraph graph, LoreGraph.Gates gates) {
        List<Identifier> out = new ArrayList<>();
        for (LoreGraph.Node node : gates.nodes()) {
            out.add(node.id());
            graph.capstoneFor(node).ifPresent(cap -> out.add(cap.id()));
        }
        return out;
    }

    public static Optional<Grade> readGrade(ItemStack stack) {
        for (GradeAccess access : GRADE_ACCESS) {
            Optional<Grade> grade = access.read(stack);
            if (grade.isPresent()) {
                return grade;
            }
        }
        return Optional.empty();
    }

    /**
     * Applies a gate result to a crafted stack: empty when locked, otherwise the grade is lowered to the
     * cap when some registered GradeAccess understands the item. Items without a grade are unchanged.
     */
    public static ItemStack apply(ItemStack result, GateResult gate) {
        if (result.isEmpty() || gate.status() == GateResult.Status.OK && gate.cap() == Grade.HEIRLOOM) {
            return result;
        }
        if (gate.locked()) {
            return ItemStack.EMPTY;
        }
        for (GradeAccess access : GRADE_ACCESS) {
            Optional<Grade> grade = access.read(result);
            if (grade.isPresent()) {
                Grade capped = grade.get().min(gate.cap());
                return capped == grade.get() ? result : access.write(result.copy(), capped);
            }
        }
        return result;
    }

    public static boolean hasGradeAccess() {
        return !GRADE_ACCESS.isEmpty();
    }

    static Item itemOf(ItemStack stack) {
        return stack.getItem();
    }
}

