package com.xxsx.earthlore.client;

import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.ItemReq;
import com.xxsx.earthlore.data.LoreCondition;
import com.xxsx.earthlore.data.LoreIngredient;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.data.ResearchCost;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.network.LorePayloads;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.registry.LoreTags;
import com.xxsx.earthlore.research.ResearchTableBlockEntity;
import com.xxsx.earthlore.research.ResearchTableMenu;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Research table screen (10 册 A-005, 06 册 §2). The left column lists what the chosen mode works on, with a
 * checklist of what the job needs; the server repeats every check when the job starts. Items marked grey
 * (made items, structures and field values) can only be judged by the server.
 */
public class ResearchTableScreen extends AbstractContainerScreen<ResearchTableMenu> {
    private static final String KEY = "screen.earth_lore.research_table.";
    private static final String[] MODES = {"research", "copy", "compile"};
    private static final int LIST_X = 8;
    private static final int LIST_Y = 20;
    private static final int LIST_W = 116;
    private static final int ROW_H = 11;
    private static final int LIST_ROWS = 6;
    private static final int CHECK_Y = 91;
    private static final int CHECK_H = 10;
    private static final int CHECK_ROWS = 4;
    private static final int STATUS_X = 132;
    private static final int STATUS_Y = 110;
    private static final int STATUS_W = 110;
    private static final int BAR_X = 206;
    private static final int BAR_Y = 80;
    private static final int BAR_W = 36;

    private record Row(Identifier id, Component label, int color, boolean selectable, boolean known) {
    }

    private record Check(Component label, Boolean ok) {
    }

    private final Button[] modeButtons = new Button[MODES.length];
    private Button treeButton;
    private Button startButton;
    private Button cancelButton;
    private int mode;
    private Identifier selected;
    private int scroll;
    private int cachedVersion = -1;
    private List<LoreGraph.Node> candidates = List.of();

    public ResearchTableScreen(ResearchTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ResearchTableMenu.IMAGE_WIDTH, ResearchTableMenu.IMAGE_HEIGHT);
        this.mode = menu.mode();
    }

    @Override
    protected void init() {
        super.init();
        treeButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "open_tree"), b -> openLoreTree())
                .bounds(leftPos + 110, topPos + 3, 38, 13)
                .tooltip(Tooltip.create(Component.translatable(KEY + "open_tree.hint")))
                .build());
        for (int i = 0; i < MODES.length; i++) {
            int index = i;
            modeButtons[i] = addRenderableWidget(Button.builder(Component.translatable(KEY + "mode." + MODES[i]), b -> setMode(index))
                    .bounds(leftPos + 150 + i * 31, topPos + 3, 30, 13)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "mode." + MODES[i] + ".hint")))
                    .build());
        }
        startButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "start"), b -> start())
                .bounds(leftPos + 172, topPos + 89, 34, 16).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "cancel"), b -> cancel())
                .bounds(leftPos + 208, topPos + 89, 34, 16)
                .tooltip(Tooltip.create(Component.translatable(KEY + "cancel.hint")))
                .build());
        refresh();
        syncButtons();
    }

    private void openLoreTree() {
        playPageSound(1.05F);
        Identifier initial = selected != null
                ? ClientKnowledge.graph().node(selected).map(LoreGraph.Node::discipline).orElse(null)
                : null;
        minecraft.setScreen(new LoreTreeScreen(this, initial));
    }

    private void playPageSound(float pitch) {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, pitch, 0.75F));
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refresh();
        syncButtons();
    }

    private boolean busy() {
        ResearchTableBlockEntity.State state = menu.state();
        return state == ResearchTableBlockEntity.State.RUNNING || state == ResearchTableBlockEntity.State.PAUSED;
    }

    /** While a job runs (or waits to be emptied) the screen shows the job's mode, otherwise the chosen one. */
    private int shownMode() {
        return menu.state() == ResearchTableBlockEntity.State.IDLE ? mode : menu.mode();
    }

    private void setMode(int next) {
        mode = next;
        scroll = 0;
        playPageSound(1.0F);
        syncButtons();
    }

    private void syncButtons() {
        if (startButton == null) {
            return;
        }
        boolean idle = menu.state() == ResearchTableBlockEntity.State.IDLE;
        int shown = shownMode();
        for (int i = 0; i < modeButtons.length; i++) {
            modeButtons[i].active = idle && i != shown;
        }
        boolean outputEmpty = slot(ResearchTableBlockEntity.SLOT_OUTPUT).isEmpty();
        boolean nodeReady = mode != ResearchTableBlockEntity.MODE_RESEARCH || selected != null;
        startButton.active = idle && outputEmpty && nodeReady;
        Component startTip;
        if (!idle) {
            startTip = Component.translatable(KEY + "start.hint.busy");
        } else if (!outputEmpty) {
            startTip = Component.translatable(KEY + "start.hint.output_full");
        } else if (!nodeReady) {
            startTip = Component.translatable(KEY + "start.hint.select");
        } else {
            startTip = Component.translatable(KEY + "start.hint.ready." + MODES[mode]);
        }
        startButton.setTooltip(Tooltip.create(startTip));
        cancelButton.active = busy();
    }

    private void start() {
        playPageSound(1.1F);
        Optional<Identifier> target = mode == ResearchTableBlockEntity.MODE_RESEARCH ? Optional.ofNullable(selected) : Optional.empty();
        ClientPacketDistributor.sendToServer(new LorePayloads.ResearchStart(menu.pos(), mode, target));
    }

    private void cancel() {
        playPageSound(0.85F);
        ClientPacketDistributor.sendToServer(new LorePayloads.ResearchCancel(menu.pos()));
    }

    private ItemStack slot(int index) {
        return menu.getSlot(index).getItem();
    }

    // ---- data ----

    private void refresh() {
        int version = ClientKnowledge.version();
        LoreGraph graph = ClientKnowledge.graph();
        if (version != cachedVersion) {
            cachedVersion = version;
            List<LoreGraph.Node> out = new ArrayList<>();
            for (LoreGraph.Node node : graph.nodes()) {
                if (!ClientKnowledge.knowsSelf(node.id()) && ClientKnowledge.prerequisitesMet(node, null)) {
                    out.add(node);
                }
            }
            out.sort(Comparator.comparingInt((LoreGraph.Node n) -> graph.discipline(n.discipline()).map(Discipline::order).orElse(99))
                    .thenComparing(LoreGraph.STUDY_ORDER));
            candidates = List.copyOf(out);
        }
        Identifier running = runningTarget(graph);
        if (running != null && menu.mode() == ResearchTableBlockEntity.MODE_RESEARCH) {
            selected = running;
        } else if (selected != null && candidates.stream().noneMatch(node -> node.id().equals(selected))) {
            selected = null;
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows().size() - LIST_ROWS)));
    }

    private Identifier runningTarget(LoreGraph graph) {
        int index = menu.targetIndex();
        List<Identifier> order = graph.order();
        return menu.state() != ResearchTableBlockEntity.State.IDLE && index >= 0 && index < order.size() ? order.get(index) : null;
    }

    private List<Row> rows() {
        LoreGraph graph = ClientKnowledge.graph();
        List<Row> out = new ArrayList<>();
        int shown = shownMode();
        if (shown == ResearchTableBlockEntity.MODE_RESEARCH) {
            for (LoreGraph.Node node : candidates) {
                out.add(new Row(node.id(), NodeTooltip.name(node), color(graph, node.discipline()), true, false));
            }
        } else if (shown == ResearchTableBlockEntity.MODE_COPY) {
            LoreText text = slot(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST).get(LoreContent.LORE_TEXT.get());
            if (text != null) {
                text.nodes().forEach(id -> out.add(textRow(graph, id)));
            }
        } else {
            Set<Identifier> seen = new LinkedHashSet<>();
            for (int s = 0; s < ResearchTableBlockEntity.MATERIAL_SLOTS; s++) {
                LoreText text = slot(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + s).get(LoreContent.LORE_TEXT.get());
                if (text != null) {
                    seen.addAll(text.nodes());
                }
            }
            ResearchTableBlockEntity.studyOrder(graph, seen).forEach(id -> out.add(textRow(graph, id)));
        }
        return out;
    }

    private static Row textRow(LoreGraph graph, Identifier id) {
        Optional<LoreGraph.Node> node = graph.node(id);
        Component label = node.map(NodeTooltip::name).orElse(Component.literal(id.toString()));
        int color = node.map(n -> color(graph, n.discipline())).orElse(0xFF808080);
        return new Row(id, label, color, false, ClientKnowledge.knowsSelf(id));
    }

    private static int color(LoreGraph graph, Identifier discipline) {
        return LoreColors.argb(graph.discipline(discipline).map(Discipline::color).orElse(0x808080));
    }

    private List<Check> checks() {
        return switch (shownMode()) {
            case ResearchTableBlockEntity.MODE_COPY -> copyChecks();
            case ResearchTableBlockEntity.MODE_COMPILE -> compileChecks();
            default -> researchChecks();
        };
    }

    private List<Check> researchChecks() {
        List<Check> out = new ArrayList<>();
        Optional<LoreGraph.Node> found = selected == null ? Optional.empty() : ClientKnowledge.graph().node(selected);
        if (found.isEmpty()) {
            out.add(new Check(Component.translatable(KEY + "check.select"), false));
            return out;
        }
        LoreGraph.Node node = found.get();
        ResearchCost cost = node.data().research();
        for (ResearchCost.Material material : cost.items()) {
            int have = 0;
            for (int s = 0; s < ResearchTableBlockEntity.MATERIAL_SLOTS; s++) {
                ItemStack stack = slot(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + s);
                if (material.ingredient().test(stack)) {
                    have += stack.getCount();
                }
            }
            out.add(new Check(Component.translatable(KEY + "check.material", ingredientName(material.ingredient()),
                    Math.min(have, material.count()), material.count()), have >= material.count()));
        }
        for (ItemReq req : node.data().itemRequires()) {
            req.hold().ifPresent(hold -> out.add(new Check(Component.translatable(KEY + "check.hold", ingredientName(hold)), holds(hold))));
            req.made().ifPresent(made -> out.add(new Check(Component.translatable(KEY + "check.made", itemName(made)), null)));
            req.cond().ifPresent(cond -> out.add(new Check(conditionLabel(cond), conditionMet(cond))));
        }
        out.add(new Check(Component.translatable(KEY + "check.carrier"), slot(ResearchTableBlockEntity.SLOT_CARRIER).is(LoreTags.CARRIERS)));
        return out;
    }

    private List<Check> copyChecks() {
        List<Check> out = new ArrayList<>();
        ItemStack original = slot(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST);
        LoreText text = original.get(LoreContent.LORE_TEXT.get());
        boolean hasText = text != null && !text.nodes().isEmpty();
        out.add(new Check(Component.translatable(KEY + "check.original"), hasText));
        out.add(new Check(Component.translatable(KEY + "check.ink"), slot(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + 1).is(Items.INK_SAC)));
        ItemStack carrier = slot(ResearchTableBlockEntity.SLOT_CARRIER);
        if (original.is(LoreContent.TREATISE.get())) {
            out.add(new Check(Component.translatable(KEY + "check.book"), carrier.is(Items.BOOK)));
        } else {
            Boolean ok = !carrier.is(LoreTags.CARRIERS) ? Boolean.FALSE
                    : original.is(LoreContent.RESEARCH_NOTES.get()) && carrier.is(LoreContent.BLANK_NOTES.get()) ? Boolean.TRUE : null;
            out.add(new Check(Component.translatable(KEY + "check.copy_carrier"), ok));
        }
        return out;
    }

    private List<Check> compileChecks() {
        List<Check> out = new ArrayList<>();
        Identifier discipline = null;
        boolean mixed = false;
        boolean invalid = false;
        int count = 0;
        Set<Identifier> distinct = new HashSet<>();
        for (int s = 0; s < ResearchTableBlockEntity.MATERIAL_SLOTS; s++) {
            ItemStack stack = slot(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + s);
            if (stack.isEmpty()) {
                continue;
            }
            LoreText text = stack.get(LoreContent.LORE_TEXT.get());
            if (text == null || text.nodes().size() != 1) {
                invalid = true;
                continue;
            }
            count++;
            if (discipline == null) {
                discipline = text.discipline();
            } else if (!discipline.equals(text.discipline())) {
                mixed = true;
            }
            distinct.add(text.nodes().get(0));
        }
        out.add(new Check(Component.translatable(KEY + "check.notes", distinct.size()), !invalid && distinct.size() >= 2));
        out.add(new Check(Component.translatable(KEY + "check.same_discipline"), !mixed));
        out.add(new Check(Component.translatable(KEY + "check.limit", LoreText.MAX_NODES), count <= LoreText.MAX_NODES));
        out.add(new Check(Component.translatable(KEY + "check.book"), slot(ResearchTableBlockEntity.SLOT_CARRIER).is(Items.BOOK)));
        return out;
    }

    private boolean holds(LoreIngredient ingredient) {
        for (int i = 0; i < ResearchTableBlockEntity.INSTRUMENT_SLOTS; i++) {
            if (ingredient.test(slot(ResearchTableBlockEntity.SLOT_INSTRUMENT_FIRST + i))) {
                return true;
            }
        }
        if (minecraft.player == null) {
            return false;
        }
        Inventory inventory = minecraft.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (ingredient.test(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** Client-side guess of a research condition; null when only the server can judge it. */
    private Boolean conditionMet(LoreCondition condition) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        BlockPos pos = menu.pos();
        return switch (condition.kind()) {
            case NIGHT -> level.isDarkOutside();
            case DAY -> level.isBrightOutside();
            case SKY -> level.canSeeSky(pos.above());
            case RAIN -> level.isRaining();
            case THUNDER -> level.isThundering();
            case Y_AT_MOST -> pos.getY() <= condition.number();
            case Y_AT_LEAST -> pos.getY() >= condition.number();
            case DIM -> level.dimension().identifier().toString().equals(condition.id());
            case BIOME -> {
                Identifier id = Identifier.tryParse(condition.id());
                Holder<Biome> biome = level.getBiome(pos);
                yield id != null && (biome.is(TagKey.create(Registries.BIOME, id)) || biome.is(ResourceKey.create(Registries.BIOME, id)));
            }
            case FIELD, AETHER, SANCTUM, STRUCTURE -> null;
            case INVALID -> false;
        };
    }

    static Component conditionLabel(LoreCondition condition) {
        String key = "condition.earth_lore." + condition.kind().name().toLowerCase(Locale.ROOT);
        return switch (condition.kind()) {
            case FIELD, AETHER, SANCTUM, Y_AT_MOST, Y_AT_LEAST -> Component.translatable(key, condition.number());
            case DIM, STRUCTURE -> Component.translatable(key, condition.id());
            case BIOME -> {
                Identifier id = Identifier.tryParse(condition.id());
                String biomeKey = id == null ? "" : "biome." + id.getNamespace() + "." + id.getPath();
                yield Component.translatable(key, I18n.exists(biomeKey) ? Component.translatable(biomeKey) : Component.literal(condition.id()));
            }
            default -> Component.translatable(key);
        };
    }

    static Component ingredientName(LoreIngredient ingredient) {
        Optional<Item> item = ingredient.asItem();
        if (item.isPresent()) {
            return new ItemStack(item.get()).getHoverName();
        }
        List<ItemStack> display = ingredient.display();
        Component first = display.isEmpty() ? Component.literal(ingredient.toString()) : display.get(0).getHoverName();
        return Component.translatable(KEY + "tag", first);
    }

    private static Component itemName(Identifier id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName())
                .orElse(Component.literal(id.toString()));
    }

    // ---- drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        LoreGui.panel(g, leftPos, topPos, imageWidth, imageHeight);
        int shown = shownMode();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (i == ResearchTableBlockEntity.SLOT_OUTPUT) {
                LoreGui.bigSlot(g, leftPos + slot.x - 5, topPos + slot.y - 5);
            } else {
                LoreGui.slot(g, leftPos + slot.x - 1, topPos + slot.y - 1);
                if (shown == ResearchTableBlockEntity.MODE_COPY
                        && i >= ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + 2
                        && i < ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + ResearchTableBlockEntity.MATERIAL_SLOTS
                        && !slot.hasItem()) {
                    g.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0x452A1F16);
                }
            }
        }
        drawSlotBanner(g, shown);
        drawList(g, mouseX, mouseY);
        drawChecks(g);
        drawStatus(g);
        drawProgress(g);
    }

    private void drawSlotBanner(GuiGraphicsExtractor g, int shown) {
        int x = leftPos + 153;
        int y = topPos + 22;
        int maxW = 88;
        Component topLine = Component.translatable(KEY + "guide." + MODES[shown]);
        g.text(font, LoreGui.fit(font, topLine, maxW), x, y, LoreGui.INK, false);
        Component subLine;
        if (shown == ResearchTableBlockEntity.MODE_RESEARCH && selected != null) {
            subLine = ClientKnowledge.graph().node(selected).map(NodeTooltip::name).orElse(Component.literal(selected.toString()));
        } else {
            subLine = Component.translatable(KEY + "guide.sub." + MODES[shown]);
        }
        g.text(font, LoreGui.fit(font, subLine, maxW), x, y + 9, LoreGui.INK_SOFT, false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, LoreGui.fit(font, title, 98), titleLabelX, titleLabelY, LoreGui.INK, false);
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;
        LoreGui.inset(g, x - 1, y - 1, LIST_W + 2, LIST_ROWS * ROW_H + 2);
        List<Row> rows = rows();
        if (rows.isEmpty()) {
            g.textWithWordWrap(font, Component.translatable(KEY + "empty." + MODES[shownMode()]), x + 3, y + 2, LIST_W - 6,
                    LoreGui.INK_SOFT, false);
            return;
        }
        boolean research = shownMode() == ResearchTableBlockEntity.MODE_RESEARCH;
        for (int i = 0; i < LIST_ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int rowY = y + i * ROW_H;
            if (research && row.id().equals(selected)) {
                g.fill(x, rowY, x + LIST_W, rowY + ROW_H, LoreGui.PAPER_SELECTED);
            } else if (row.selectable() && !busy() && LoreGui.inside(mouseX, mouseY, x, rowY, LIST_W, ROW_H)) {
                g.fill(x, rowY, x + LIST_W, rowY + ROW_H, LoreGui.PAPER_LIGHT);
            }
            g.fill(x + 2, rowY + 2, x + 8, rowY + 8, row.color());
            g.text(font, LoreGui.fit(font, row.label(), LIST_W - 14), x + 11, rowY + 2, row.known() ? LoreGui.OK : LoreGui.INK, false);
        }
        if (rows.size() > LIST_ROWS) {
            int track = LIST_ROWS * ROW_H;
            int thumb = Math.max(6, track * LIST_ROWS / rows.size());
            int thumbY = y + (track - thumb) * scroll / (rows.size() - LIST_ROWS);
            g.fill(x + LIST_W - 2, thumbY, x + LIST_W, thumbY + thumb, LoreGui.WALNUT);
        }
    }

    private void drawChecks(GuiGraphicsExtractor g) {
        int x = leftPos + LIST_X;
        int y = topPos + CHECK_Y;
        LoreGui.inset(g, x - 1, y - 1, LIST_W + 2, CHECK_ROWS * CHECK_H + 2);
        List<Check> checks = checks();
        int shown = checks.size() > CHECK_ROWS ? CHECK_ROWS - 1 : checks.size();
        for (int i = 0; i < shown; i++) {
            Check check = checks.get(i);
            int rowY = y + i * CHECK_H;
            LoreGui.dot(g, x + 2, rowY + 2, check.ok());
            g.text(font, LoreGui.fit(font, check.label(), LIST_W - 12), x + 10, rowY + 1,
                    check.ok() == null ? LoreGui.INK_SOFT : LoreGui.INK, false);
        }
        if (checks.size() > CHECK_ROWS) {
            g.text(font, Component.translatable(KEY + "more", checks.size() - shown), x + 10, y + shown * CHECK_H + 1, LoreGui.INK_SOFT, false);
        }
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        int x = leftPos + STATUS_X;
        int y = topPos + STATUS_Y;
        ResearchTableBlockEntity.State state = menu.state();
        Component line = Component.translatable(KEY + "state." + state.id());
        if (busy()) {
            LoreGraph graph = ClientKnowledge.graph();
            Identifier target = runningTarget(graph);
            Component name = target == null ? Component.empty() : graph.node(target).map(NodeTooltip::name).orElse(Component.literal(target.toString()));
            int percent = menu.progress() * 100 / Math.max(1, menu.total());
            line = Component.translatable(KEY + "state.busy", line, name, percent);
        }
        int color = switch (state) {
            case RUNNING -> LoreGui.OK;
            case PAUSED -> LoreGui.WARN;
            case DONE -> LoreGui.BRASS;
            case IDLE -> LoreGui.INK;
        };
        g.text(font, LoreGui.fit(font, line, STATUS_W), x, y, color, false);
        if (state == ResearchTableBlockEntity.State.PAUSED && !menu.pauseReason().isEmpty()) {
            g.text(font, LoreGui.fit(font, Component.translatable("lore.earth_lore.fail." + menu.pauseReason()), STATUS_W), x, y + 10,
                    LoreGui.WARN, false);
        } else if (state == ResearchTableBlockEntity.State.RUNNING && menu.total() > 0) {
            int remainSec = Math.max(0, (menu.total() - menu.progress() + 19) / 20);
            Component timeLine = Component.translatable(KEY + "state.remain", remainSec);
            g.text(font, LoreGui.fit(font, timeLine, STATUS_W), x, y + 10, LoreGui.INK_SOFT, false);
        } else if (state == ResearchTableBlockEntity.State.IDLE && !menu.lastFail().isEmpty()) {
            g.text(font, LoreGui.fit(font, Component.translatable(KEY + "last_fail",
                    Component.translatable("lore.earth_lore.fail." + menu.lastFail())), STATUS_W), x, y + 10, LoreGui.BAD, false);
        }
    }

    private void drawProgress(GuiGraphicsExtractor g) {
        int arrowX = leftPos + 205;
        int arrowY = topPos + 61;
        g.fill(arrowX, arrowY, arrowX + 9, arrowY + 2, LoreGui.PAPER_SHADE);
        g.fill(arrowX + 7, arrowY - 2, arrowX + 8, arrowY + 4, LoreGui.PAPER_SHADE);
        g.fill(arrowX + 8, arrowY - 1, arrowX + 9, arrowY + 3, LoreGui.PAPER_SHADE);

        int x = leftPos + BAR_X;
        int y = topPos + BAR_Y;
        g.fill(x - 1, y - 1, x + BAR_W + 1, y + 6, LoreGui.WALNUT);
        g.fill(x, y, x + BAR_W, y + 5, 0xFF2A1F16);
        ResearchTableBlockEntity.State state = menu.state();
        if (state == ResearchTableBlockEntity.State.DONE) {
            g.fill(x, y, x + BAR_W, y + 5, LoreGui.BRASS);
            g.fill(arrowX, arrowY, arrowX + 9, arrowY + 2, LoreGui.BRASS);
            return;
        }
        if (menu.total() <= 0 || state == ResearchTableBlockEntity.State.IDLE) {
            return;
        }
        LoreGraph graph = ClientKnowledge.graph();
        Identifier target = runningTarget(graph);
        int rgb = target == null ? 0xB5893B : graph.node(target)
                .flatMap(node -> graph.discipline(node.discipline())).map(d -> LoreColors.highlight(d.color())).orElse(0xB5893B);
        int filled = BAR_W * Math.min(menu.progress(), menu.total()) / menu.total();
        int barColor = state == ResearchTableBlockEntity.State.PAUSED ? LoreGui.WARN : LoreColors.argb(rgb);
        g.fill(x, y, x + filled, y + 5, barColor);
        int arrowFilled = 9 * Math.min(menu.progress(), menu.total()) / menu.total();
        if (arrowFilled > 0) {
            g.fill(arrowX, arrowY, arrowX + arrowFilled, arrowY + 2, barColor);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int x = leftPos + LIST_X;
        int listY = topPos + LIST_Y;
        if (LoreGui.inside(mouseX, mouseY, x, listY, LIST_W, LIST_ROWS * ROW_H)) {
            List<Row> rows = rows();
            int index = scroll + (mouseY - listY) / ROW_H;
            if (index >= 0 && index < rows.size()) {
                LoreGraph graph = ClientKnowledge.graph();
                graph.node(rows.get(index).id()).ifPresent(node ->
                        g.setTooltipForNextFrame(font, NodeTooltip.lines(font, graph, node), mouseX, mouseY));
            }
        } else if (LoreGui.inside(mouseX, mouseY, x, topPos + CHECK_Y, LIST_W, CHECK_ROWS * CHECK_H)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable(KEY + "checks"));
            for (Check check : checks()) {
                ChatFormatting style = check.ok() == null ? ChatFormatting.GRAY : check.ok() ? ChatFormatting.GREEN : ChatFormatting.RED;
                String mark = check.ok() == null ? "? " : check.ok() ? "\u2714 " : "\u2718 ";
                lines.add(Component.literal(mark).append(check.label()).withStyle(style));
            }
            lines.add(Component.translatable(KEY + "checks.server").withStyle(ChatFormatting.DARK_GRAY));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        } else if (LoreGui.inside(mouseX, mouseY, leftPos + BAR_X - 1, topPos + BAR_Y - 2, BAR_W + 2, 9) && menu.total() > 0) {
            int percent = menu.progress() * 100 / Math.max(1, menu.total());
            int remainSec = Math.max(0, (menu.total() - menu.progress() + 19) / 20);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable(KEY + "progress.tooltip", menu.progress(), menu.total(), percent, remainSec)
                    .withStyle(ChatFormatting.GOLD));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        } else if (hoveredSlot != null && !hoveredSlot.hasItem()) {
            int index = menu.slots.indexOf(hoveredSlot);
            List<Component> slotTip = slotTooltip(index);
            if (!slotTip.isEmpty()) {
                g.setComponentTooltipForNextFrame(font, slotTip, mouseX, mouseY);
            }
        }
    }

    private List<Component> slotTooltip(int index) {
        String hint = slotHint(index);
        if (hint == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(hint).withStyle(ChatFormatting.GOLD));
        if (shownMode() == ResearchTableBlockEntity.MODE_RESEARCH && selected != null) {
            ClientKnowledge.graph().node(selected).ifPresent(node -> {
                if (index >= ResearchTableBlockEntity.SLOT_MATERIAL_FIRST
                        && index < ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + ResearchTableBlockEntity.MATERIAL_SLOTS) {
                    for (ResearchCost.Material m : node.data().research().items()) {
                        lines.add(Component.literal(" • ")
                                .append(ingredientName(m.ingredient()))
                                .append(Component.literal(" x" + m.count()))
                                .withStyle(ChatFormatting.GRAY));
                    }
                } else if (index >= ResearchTableBlockEntity.SLOT_INSTRUMENT_FIRST
                        && index < ResearchTableBlockEntity.SLOT_INSTRUMENT_FIRST + ResearchTableBlockEntity.INSTRUMENT_SLOTS) {
                    for (ItemReq req : node.data().itemRequires()) {
                        req.hold().ifPresent(hold -> lines.add(Component.literal(" • ")
                                .append(ingredientName(hold))
                                .withStyle(ChatFormatting.GRAY)));
                    }
                }
            });
        }
        return lines;
    }

    private String slotHint(int index) {
        int shown = shownMode();
        if (index == ResearchTableBlockEntity.SLOT_CARRIER) {
            return KEY + "slot.carrier." + MODES[shown];
        }
        if (index == ResearchTableBlockEntity.SLOT_MATERIAL_FIRST && shown == ResearchTableBlockEntity.MODE_COPY) {
            return KEY + "slot.original";
        }
        if (index == ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + 1 && shown == ResearchTableBlockEntity.MODE_COPY) {
            return KEY + "slot.ink";
        }
        if (index >= ResearchTableBlockEntity.SLOT_MATERIAL_FIRST
                && index < ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + ResearchTableBlockEntity.MATERIAL_SLOTS) {
            return KEY + "slot.material." + MODES[shown];
        }
        if (index >= ResearchTableBlockEntity.SLOT_INSTRUMENT_FIRST
                && index < ResearchTableBlockEntity.SLOT_INSTRUMENT_FIRST + ResearchTableBlockEntity.INSTRUMENT_SLOTS) {
            return KEY + "slot.instrument";
        }
        return index == ResearchTableBlockEntity.SLOT_OUTPUT ? KEY + "slot.output" : null;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && shownMode() == ResearchTableBlockEntity.MODE_RESEARCH && !busy()) {
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y;
            if (LoreGui.inside(event.x(), event.y(), x, y, LIST_W, LIST_ROWS * ROW_H)) {
                List<Row> rows = rows();
                int index = scroll + (int) ((event.y() - y) / ROW_H);
                if (index >= 0 && index < rows.size() && rows.get(index).selectable()) {
                    Identifier id = rows.get(index).id();
                    selected = id.equals(selected) ? null : id;
                    playPageSound(1.0F);
                    syncButtons();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (LoreGui.inside(mouseX, mouseY, leftPos + LIST_X, topPos + LIST_Y, LIST_W, LIST_ROWS * ROW_H)) {
            int max = Math.max(0, rows().size() - LIST_ROWS);
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}

