package com.xxsx.earthlore.client;

import com.xxsx.earthlore.api.GateResult;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.registry.LoreContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Literature lines (A-004 rule 4, A-007), always-visible gate status + hold-W jump hint, and Shift breakdown. */
final class ClientTooltips {
    private ClientTooltips() {
    }

    static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }
        List<Component> lines = event.getToolTip();
        LoreGraph graph = ClientKnowledge.graph();
        LoreText text = stack.get(LoreContent.LORE_TEXT.get());
        List<Component> extra = new ArrayList<>();
        if (text != null) {
            literature(graph, text, extra);
        }
        LoreGraph.Gates gates = ClientKnowledge.gates(stack);
        if (!gates.isEmpty()) {
            gateLines(graph, gates, extra);
        }
        if (!extra.isEmpty()) {
            if (ClientKnowledgeInspect.primaryNodeFor(stack).isPresent()) {
                extra.add(inspectHintLine());
            }
            lines.addAll(Math.min(1, lines.size()), extra);
        }
    }

    static Component inspectHintLine() {
        return Component.translatable("tooltip.earth_lore.inspect.hint",
                ClientLore.INSPECT_KNOWLEDGE_KEY.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    static Component disciplineName(LoreGraph graph, Identifier id) {
        Optional<Discipline> discipline = graph.discipline(id);
        return discipline.map(d -> (Component) Component.translatable(d.nameKey()).withColor(LoreColors.onDark(d)))
                .orElse(Component.literal(id.toString()));
    }

    private static void literature(LoreGraph graph, LoreText text, List<Component> out) {
        out.add(disciplineName(graph, text.discipline()));
        for (Identifier id : text.nodes()) {
            Optional<LoreGraph.Node> node = graph.node(id);
            if (node.isEmpty()) {
                String mod = ModList.get().getModContainerById(id.getNamespace())
                        .map(container -> container.getModInfo().getDisplayName()).orElse(id.getNamespace());
                out.add(Component.literal(" - ").append(Component.translatable("tooltip.earth_lore.needs_mod", mod))
                        .withStyle(ChatFormatting.DARK_GRAY));
                continue;
            }
            MutableComponent line = Component.literal(" - ").append(Component.translatable(node.get().nameKey()))
                    .append(Component.translatable("tooltip.earth_lore.tier", node.get().tier()));
            if (ClientKnowledge.knowsSelf(id)) {
                out.add(line.append(Component.translatable("tooltip.earth_lore.known")).withStyle(ChatFormatting.GREEN));
                continue;
            }
            out.add(line.withStyle(ChatFormatting.GRAY));
            List<Identifier> missing = new ArrayList<>();
            if (!ClientKnowledge.prerequisitesMet(node.get(), missing) && !missing.isEmpty()) {
                MutableComponent need = Component.translatable("tooltip.earth_lore.missing");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) {
                        need.append(", ");
                    }
                    Identifier missingId = missing.get(i);
                    need.append(graph.node(missingId).map(n -> (Component) Component.translatable(n.nameKey()))
                            .orElse(Component.literal(missingId.toString())));
                }
                out.add(Component.literal("   ").append(need).withStyle(ChatFormatting.RED));
            }
        }
    }

    private static void gateLines(LoreGraph graph, LoreGraph.Gates gates, List<Component> out) {
        List<LoreGraph.Node> allNodes = gates.nodes();
        List<LoreGraph.Node> missingNodes = new ArrayList<>();
        int knownCount = 0;
        for (LoreGraph.Node node : allNodes) {
            if (ClientKnowledge.knows(node.id())) {
                knownCount++;
            } else {
                missingNodes.add(node);
            }
        }
        GateResult result = ClientKnowledge.check(gates);
        if (!Minecraft.getInstance().hasShiftDown()) {
            if (result.locked() || !missingNodes.isEmpty()) {
                MutableComponent missingNames = Component.empty();
                int limit = Math.min(2, missingNodes.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) {
                        missingNames.append("、");
                    }
                    missingNames.append(Component.translatable(missingNodes.get(i).nameKey()));
                }
                if (missingNodes.size() > limit) {
                    missingNames.append("...");
                }
                out.add(Component.translatable("tooltip.earth_lore.gate.summary_locked",
                        missingNames, knownCount, allNodes.size())
                        .withStyle(result.locked() ? ChatFormatting.RED : ChatFormatting.GOLD));
            } else if (!allNodes.isEmpty()) {
                out.add(Component.translatable("tooltip.earth_lore.gate.summary_known",
                        Component.translatable(allNodes.get(0).nameKey()), knownCount, allNodes.size())
                        .withStyle(ChatFormatting.GREEN));
            }
            out.add(Component.translatable("tooltip.earth_lore.gate.hint").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        for (LoreGraph.Node node : allNodes) {
            boolean known = ClientKnowledge.knows(node.id());
            out.add(Component.translatable("tooltip.earth_lore.gate.node", Component.translatable(node.nameKey()),
                    disciplineName(graph, node.discipline()), Component.translatable("tooltip.earth_lore.gate." + node.gate().id()))
                    .withStyle(known ? ChatFormatting.GREEN : ChatFormatting.GOLD));
            if (!known) {
                List<Identifier> missingPrereqs = new ArrayList<>();
                if (!ClientKnowledge.prerequisitesMet(node, missingPrereqs) && !missingPrereqs.isEmpty()) {
                    MutableComponent prereqNames = Component.empty();
                    for (int i = 0; i < missingPrereqs.size(); i++) {
                        if (i > 0) {
                            prereqNames.append(", ");
                        }
                        Identifier pid = missingPrereqs.get(i);
                        prereqNames.append(graph.node(pid).map(n -> (Component) Component.translatable(n.nameKey()))
                                .orElse(Component.literal(pid.toString())));
                    }
                    out.add(Component.literal("   ")
                            .append(Component.translatable("tooltip.earth_lore.missing"))
                            .append(prereqNames)
                            .withStyle(ChatFormatting.RED));
                }
            }
        }
        if (gates.lockedByRejected()) {
            out.add(Component.translatable("tooltip.earth_lore.gate.rejected").withStyle(ChatFormatting.RED));
        }
        out.add(result.locked()
                ? Component.translatable("tooltip.earth_lore.gate.locked").withStyle(ChatFormatting.RED)
                : Component.translatable("tooltip.earth_lore.gate.max_grade",
                        Component.translatable("grade.earth_lore." + result.cap().id())).withStyle(ChatFormatting.AQUA));
    }
}
