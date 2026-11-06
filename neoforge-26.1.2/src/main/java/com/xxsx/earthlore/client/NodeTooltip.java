package com.xxsx.earthlore.client;

import com.xxsx.earthlore.core.Gate;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.UnlockRef;
import com.xxsx.earthlore.graph.LoreGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Hover text of a knowledge node in the lore tree and the research table list (10 册 A-018). */
final class NodeTooltip {
    private static final int WIDTH = 220;
    private static final int MAX_UNLOCKS = 5;

    enum State {
        SELF, OTHERS, AVAILABLE, MISSING;

        String key() {
            return "screen.earth_lore.tree.state." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private NodeTooltip() {
    }

    static State state(LoreGraph.Node node) {
        if (ClientKnowledge.knowsSelf(node.id())) {
            return State.SELF;
        }
        if (ClientKnowledge.knows(node.id())) {
            return State.OTHERS;
        }
        return ClientKnowledge.prerequisitesMet(node, null) ? State.AVAILABLE : State.MISSING;
    }

    static Component name(LoreGraph.Node node) {
        return Component.translatable(node.nameKey());
    }

    static List<FormattedCharSequence> lines(Font font, LoreGraph graph, LoreGraph.Node node) {
        List<Component> out = new ArrayList<>();
        Optional<Discipline> discipline = graph.discipline(node.discipline());
        int color = discipline.map(LoreColors::onDark).orElse(0xFFFFFF);
        out.add(name(node).copy().withColor(color).withStyle(ChatFormatting.BOLD));
        out.add(Component.translatable("screen.earth_lore.tree.meta", ClientTooltips.disciplineName(graph, node.discipline()),
                node.tier(), Component.translatable("tooltip.earth_lore.gate." + node.gate().id())).withStyle(ChatFormatting.GRAY));
        State state = state(node);
        ChatFormatting stateColor = switch (state) {
            case SELF -> ChatFormatting.GREEN;
            case OTHERS -> ChatFormatting.AQUA;
            case AVAILABLE -> ChatFormatting.WHITE;
            case MISSING -> ChatFormatting.RED;
        };
        out.add(Component.translatable(state.key()).withStyle(stateColor));
        if (state == State.MISSING) {
            List<Identifier> missing = new ArrayList<>();
            ClientKnowledge.prerequisitesMet(node, missing);
            if (!missing.isEmpty()) {
                out.add(Component.translatable("tooltip.earth_lore.missing").append(join(graph, missing)).withStyle(ChatFormatting.RED));
            }
        }
        if (I18n.exists(node.descKey())) {
            out.add(Component.translatable(node.descKey()).withStyle(ChatFormatting.DARK_AQUA));
        }
        unlocks(node, out);
        teachers(node, out);
        if (node.gate() == Gate.ROOT) {
            out.add(Component.translatable("screen.earth_lore.tree.root").withStyle(ChatFormatting.DARK_GRAY));
        } else if (state == State.AVAILABLE) {
            out.add(Component.translatable("screen.earth_lore.tree.how").withStyle(ChatFormatting.DARK_GRAY));
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : out) {
            lines.addAll(font.split(line, WIDTH));
        }
        return lines;
    }

    static MutableComponent join(LoreGraph graph, List<Identifier> ids) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(Component.translatable("screen.earth_lore.tree.separator"));
            }
            Identifier id = ids.get(i);
            out.append(graph.node(id).map(NodeTooltip::name).orElse(Component.literal(id.toString())));
        }
        return out;
    }

    private static void unlocks(LoreGraph.Node node, List<Component> out) {
        List<Component> names = new ArrayList<>();
        int other = 0;
        for (UnlockRef unlock : node.data().unlocks()) {
            Identifier id = Identifier.tryParse(unlock.value());
            Optional<Component> name = switch (unlock.kind()) {
                case ITEM, READOUT -> id == null ? Optional.empty()
                        : BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName());
                case BLOCK, YIELD -> id == null ? Optional.empty()
                        : BuiltInRegistries.BLOCK.getOptional(id).map(block -> (Component) block.getName());
                default -> Optional.empty();
            };
            if (name.isPresent() && names.size() < MAX_UNLOCKS) {
                names.add(name.get());
            } else {
                other++;
            }
        }
        if (names.isEmpty() && other == 0) {
            return;
        }
        MutableComponent line = Component.translatable("screen.earth_lore.tree.unlocks");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                line.append(Component.translatable("screen.earth_lore.tree.separator"));
            }
            line.append(names.get(i));
        }
        if (other > 0) {
            line.append(Component.translatable("screen.earth_lore.tree.unlocks_more", other));
        }
        out.add(line.withStyle(ChatFormatting.GOLD));
    }

    private static void teachers(LoreGraph.Node node, List<Component> out) {
        if (node.data().teachers().isEmpty()) {
            return;
        }
        MutableComponent line = Component.translatable("screen.earth_lore.tree.teachers");
        List<Identifier> teachers = node.data().teachers();
        for (int i = 0; i < teachers.size(); i++) {
            if (i > 0) {
                line.append(Component.translatable("screen.earth_lore.tree.separator"));
            }
            line.append(teacherName(teachers.get(i)));
        }
        out.add(line.withStyle(ChatFormatting.YELLOW));
    }

    /** Teachers are villager professions or entity types (A-019); both have standard translation keys. */
    private static Component teacherName(Identifier id) {
        String profession = "entity.minecraft.villager." + (id.getNamespace().equals("minecraft") ? "" : id.getNamespace() + ".")
                + id.getPath();
        if (I18n.exists(profession)) {
            return Component.translatable(profession);
        }
        String entity = "entity." + id.getNamespace() + "." + id.getPath();
        return I18n.exists(entity) ? Component.translatable(entity) : Component.literal(id.toString());
    }
}

