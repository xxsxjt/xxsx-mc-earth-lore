package com.xxsx.earthlore.client;

import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.graph.LoreGraph;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

/**
 * Lore tree (10 册 A-018, 06 册 §1.1 rule 3). One page per discipline with nodes in tier columns. Borders use the
 * discipline's main color; the fill shows the state: own knowledge in the main color, knowledge provided by
 * teammates or the server in the main color with a team mark, studyable nodes white and nodes with missing
 * prerequisites grey. Prerequisites from other disciplines appear as dashed "visitor" boxes in their own color;
 * clicking one opens its page. Dashed lines are "any one of" prerequisites.
 */
public class LoreTreeScreen extends Screen {
    private static final String KEY = "screen.earth_lore.tree.";
    private static final int TAB = 20;
    private static final int TAB_Y = 4;
    private static final int HEADER_Y = 28;
    private static final int TOP = 40;
    private static final int SIDE = 8;
    private static final int FOOTER = 16;
    private static final int COL_W = 122;
    private static final int NODE_W = 102;
    private static final int NODE_H = 20;
    private static final int ROW_H = 26;
    private static final int TITLE_H = 16;
    private static final int PAD = 8;
    private static final int BODY_FILL = 0xD0101418;
    private static final int FRAME = 0xFF3A3F46;

    /**
     * Priority order for core open branches:
     * 基础通识 (che/eng/agr) -> 地质冶金 (geo/mat) -> 传统医药 (med) -> 锻体武学 (som) -> 玄门修真 (xua) -> 奥术魔法 (mag) -> 神学祝祷 (the).
     */
    private static final List<String> CORE_BRANCH_PRIORITY = List.of(
            "che", "eng", "agr",
            "geo", "mat",
            "med",
            "som",
            "xua",
            "mag",
            "the"
    );

    private static Identifier lastDiscipline;

    private record Placed(LoreGraph.Node node, int x, int y, boolean visitor) {
    }

    private record Edge(Placed from, Placed to, boolean any) {
    }

    private final Screen parent;
    private Button hubButton;
    private List<Identifier> tabs = List.of();
    private Identifier current;
    private List<Placed> placed = List.of();
    private Map<Identifier, Placed> byId = Map.of();
    private List<Edge> edges = List.of();
    private List<Integer> columnTiers = List.of();
    private int contentW;
    private int contentH;
    private double scrollX;
    private double scrollY;
    private boolean dragging;
    private int cachedVersion = -1;
    private Identifier cachedDiscipline;

    public LoreTreeScreen() {
        this(null, null);
    }

    public LoreTreeScreen(Identifier initialDiscipline) {
        this(null, initialDiscipline);
    }

    public LoreTreeScreen(Screen parent, Identifier initialDiscipline) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        if (initialDiscipline != null) {
            this.current = initialDiscipline;
            lastDiscipline = initialDiscipline;
        }
    }

    @Override
    protected void init() {
        hubButton = null;
        if (canOpenCultivationHub()) {
            Component btnLabel = Component.translatable(KEY + "back_to_hub");
            int btnW = Math.min(Math.max(76, font.width(btnLabel) + 12), Math.max(76, width / 3));
            hubButton = addRenderableWidget(Button.builder(btnLabel, b -> openCultivationHub())
                    .bounds(width - SIDE - btnW, TAB_Y, btnW, TAB)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "back_to_hub.tip")))
                    .build());
        }
        LoreGraph graph = ClientKnowledge.graph();
        tabs = visibleDisciplines(graph);
        if (current == null || !tabs.contains(current) || (!isDisciplineOpen(graph, current) && firstWithNodes(graph) != null)) {
            current = lastDiscipline != null && tabs.contains(lastDiscipline) && isDisciplineOpen(graph, lastDiscipline)
                    ? lastDiscipline
                    : firstWithNodes(graph);
        }
        rebuild();
    }

    private boolean canOpenCultivationHub() {
        return parent != null || ModList.get().isLoaded("earth_human");
    }

    private void openCultivationHub() {
        playPageSound(1.05F);
        if (parent != null) {
            minecraft.setScreen(parent);
            return;
        }
        try {
            Class<?> hubClass = Class.forName("com.xxsx.earthhuman.client.CultivationHubScreen");
            Constructor<?> ctor = hubClass.getConstructor(Screen.class);
            Screen hub = (Screen) ctor.newInstance(this);
            minecraft.setScreen(hub);
        } catch (ReflectiveOperationException ignored) {
            onClose();
        }
    }

    private void playPageSound(float pitch) {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, pitch, 0.75F));
        }
    }

    /**
     * True when the discipline has loaded nodes or its home/base mod is currently installed.
     * Isolated addon disciplines (earth_life_sciences / earth_physical_sciences without base_mod nodes) return false.
     */
    public static boolean isDisciplineOpen(LoreGraph graph, Identifier id) {
        if (!graph.inDiscipline(id).isEmpty()) {
            return true;
        }
        return graph.discipline(id)
                .map(d -> ModList.get().isLoaded(d.homeMod())
                        || (!d.baseMod().isEmpty() && ModList.get().isLoaded(d.baseMod())))
                .orElse(false);
    }

    private static boolean isHomeModLoaded(LoreGraph graph, Identifier id) {
        return graph.discipline(id).map(d -> ModList.get().isLoaded(d.homeMod())).orElse(true);
    }

    private static int corePriorityIndex(Identifier id) {
        int idx = CORE_BRANCH_PRIORITY.indexOf(id.getPath());
        return idx >= 0 ? idx : 50;
    }

    /** Core branch category key for UI badges and tooltips. */
    private static Optional<String> coreBranchCategoryKey(Identifier id) {
        return switch (id.getPath()) {
            case "che", "eng", "agr" -> Optional.of(KEY + "branch.general");
            case "geo", "mat" -> Optional.of(KEY + "branch.geo_metal");
            case "med" -> Optional.of(KEY + "branch.medicine");
            case "som" -> Optional.of(KEY + "branch.somatics");
            case "xua" -> Optional.of(KEY + "branch.xuanhuan");
            case "mag" -> Optional.of(KEY + "branch.magic");
            case "the" -> Optional.of(KEY + "branch.theology");
            default -> Optional.empty();
        };
    }

    /**
     * Orders active core branches first (prioritizing the 7 open core branch families),
     * followed by isolated/unloaded addon disciplines shown greyed-out with "对应学科扩展未装载".
     */
    private static List<Identifier> visibleDisciplines(LoreGraph graph) {
        List<Identifier> all = new ArrayList<>(graph.disciplineOrder());
        all.sort(Comparator
                .comparing((Identifier id) -> !isDisciplineOpen(graph, id))
                .thenComparingInt(LoreTreeScreen::corePriorityIndex)
                .thenComparingInt(id -> graph.discipline(id).map(Discipline::order).orElse(99)));
        return List.copyOf(all);
    }

    private Identifier firstWithNodes(LoreGraph graph) {
        for (Identifier id : tabs) {
            if (!graph.inDiscipline(id).isEmpty()) {
                return id;
            }
        }
        return tabs.isEmpty() ? null : tabs.get(0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- layout ----

    private void rebuild() {
        LoreGraph graph = ClientKnowledge.graph();
        cachedVersion = ClientKnowledge.version();
        tabs = visibleDisciplines(graph);
        if (current != null && !tabs.contains(current)) {
            current = firstWithNodes(graph);
        }
        cachedDiscipline = current;
        if (current == null) {
            placed = List.of();
            byId = Map.of();
            edges = List.of();
            columnTiers = List.of();
            contentW = 0;
            contentH = 0;
            return;
        }
        Map<Identifier, LoreGraph.Node> members = new LinkedHashMap<>();
        Set<Identifier> visitors = new HashSet<>();
        List<LoreGraph.Node> own = graph.inDiscipline(current);
        own.forEach(node -> members.put(node.id(), node));
        for (LoreGraph.Node node : own) {
            for (Prereq prereq : node.def().requires()) {
                for (String raw : prereq.members()) {
                    Identifier id = Identifier.tryParse(raw);
                    if (id != null && !members.containsKey(id)) {
                        graph.node(id).ifPresent(parent -> {
                            members.put(parent.id(), parent);
                            visitors.add(parent.id());
                        });
                    }
                }
            }
        }
        TreeSet<Integer> tiers = new TreeSet<>();
        members.values().forEach(node -> tiers.add(node.tier()));
        columnTiers = List.copyOf(tiers);
        Map<Integer, List<LoreGraph.Node>> columns = new HashMap<>();
        for (LoreGraph.Node node : members.values()) {
            columns.computeIfAbsent(columnTiers.indexOf(node.tier()), ignored -> new ArrayList<>()).add(node);
        }
        Map<Identifier, Integer> rowOf = new HashMap<>();
        List<Placed> out = new ArrayList<>();
        int maxRows = 0;
        for (int c = 0; c < columnTiers.size(); c++) {
            List<LoreGraph.Node> column = new ArrayList<>(columns.getOrDefault(c, List.of()));
            Map<Identifier, Double> weight = new HashMap<>();
            for (LoreGraph.Node node : column) {
                weight.put(node.id(), visitors.contains(node.id()) ? -1.0 : barycenter(node, rowOf));
            }
            column.sort(Comparator.comparingDouble((LoreGraph.Node node) -> weight.get(node.id()))
                    .thenComparing(node -> node.id().toString()));
            for (int r = 0; r < column.size(); r++) {
                LoreGraph.Node node = column.get(r);
                rowOf.put(node.id(), r);
                out.add(new Placed(node, PAD + c * COL_W, TITLE_H + r * ROW_H, visitors.contains(node.id())));
            }
            maxRows = Math.max(maxRows, column.size());
        }
        Map<Identifier, Placed> index = new HashMap<>();
        out.forEach(p -> index.put(p.node().id(), p));
        List<Edge> lines = new ArrayList<>();
        for (Placed child : out) {
            if (child.visitor()) {
                continue;
            }
            for (Prereq prereq : child.node().def().requires()) {
                boolean any = prereq instanceof Prereq.Any && prereq.members().size() > 1;
                for (String raw : prereq.members()) {
                    Identifier id = Identifier.tryParse(raw);
                    Placed parent = id == null ? null : index.get(id);
                    if (parent != null) {
                        lines.add(new Edge(parent, child, any));
                    }
                }
            }
        }
        placed = List.copyOf(out);
        byId = index;
        edges = List.copyOf(lines);
        contentW = columnTiers.isEmpty() ? 0 : PAD * 2 + (columnTiers.size() - 1) * COL_W + NODE_W;
        contentH = TITLE_H + maxRows * ROW_H + PAD;
        clampScroll();
    }

    /** Average row of the prerequisites already placed, so children sit near their parents. */
    private static double barycenter(LoreGraph.Node node, Map<Identifier, Integer> rowOf) {
        double sum = 0;
        int count = 0;
        for (Prereq prereq : node.def().requires()) {
            for (String raw : prereq.members()) {
                Identifier id = Identifier.tryParse(raw);
                Integer row = id == null ? null : rowOf.get(id);
                if (row != null) {
                    sum += row;
                    count++;
                }
            }
        }
        return count == 0 ? 1_000_000.0 : sum / count;
    }

    private int bodyW() {
        return Math.max(0, width - SIDE * 2);
    }

    private int bodyH() {
        return Math.max(0, height - FOOTER - TOP);
    }

    private void clampScroll() {
        scrollX = Math.max(0, Math.min(scrollX, Math.max(0, contentW - bodyW())));
        scrollY = Math.max(0, Math.min(scrollY, Math.max(0, contentH - bodyH())));
    }

    private void select(Identifier discipline) {
        if (discipline == null || discipline.equals(current)) {
            return;
        }
        current = discipline;
        if (isDisciplineOpen(ClientKnowledge.graph(), discipline)) {
            lastDiscipline = discipline;
        }
        scrollX = 0;
        scrollY = 0;
        playPageSound(1.0F);
        rebuild();
    }

    private void jumpTo(LoreGraph.Node node) {
        select(node.discipline());
        Placed target = byId.get(node.id());
        if (target != null) {
            scrollX = target.x() + NODE_W / 2.0 - bodyW() / 2.0;
            scrollY = target.y() + NODE_H / 2.0 - bodyH() / 2.0;
            clampScroll();
        }
    }

    private void cycle(int step) {
        if (tabs.isEmpty()) {
            return;
        }
        LoreGraph graph = ClientKnowledge.graph();
        List<Identifier> openTabs = tabs.stream().filter(id -> isDisciplineOpen(graph, id)).toList();
        List<Identifier> pool = openTabs.isEmpty() ? tabs : openTabs;
        int at = Math.max(0, pool.indexOf(current));
        select(pool.get(Math.floorMod(at + step, pool.size())));
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (cachedVersion != ClientKnowledge.version() || !Objects.equals(cachedDiscipline, current)) {
            rebuild();
        }
        LoreGraph graph = ClientKnowledge.graph();
        int hoveredTab = drawTabs(g, graph, mouseX, mouseY);
        drawHeader(g, graph);
        int x0 = SIDE;
        int y0 = TOP;
        int x1 = SIDE + bodyW();
        int y1 = TOP + bodyH();
        g.fill(x0, y0, x1, y1, BODY_FILL);
        g.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, FRAME);
        Placed hovered = null;
        g.enableScissor(x0, y0, x1, y1);
        int ox = x0 - (int) scrollX;
        int oy = y0 - (int) scrollY;
        for (int c = 0; c < columnTiers.size(); c++) {
            g.text(font, Component.translatable(KEY + "tier", columnTiers.get(c)), ox + PAD + c * COL_W, oy + 3, 0xFF9AA0A6, false);
        }
        boolean inBody = LoreGui.inside(mouseX, mouseY, x0, y0, x1 - x0, y1 - y0);
        for (Placed p : placed) {
            boolean hover = inBody && LoreGui.inside(mouseX, mouseY, ox + p.x(), oy + p.y(), NODE_W, NODE_H);
            if (hover) {
                hovered = p;
            }
        }
        for (Edge edge : edges) {
            boolean activeEdge = hovered != null && (hovered.equals(edge.from()) || hovered.equals(edge.to()));
            drawEdge(g, graph, edge, ox, oy, activeEdge);
        }
        for (Placed p : placed) {
            boolean hover = hovered == p;
            drawNode(g, graph, p, ox + p.x(), oy + p.y(), hover);
        }
        g.disableScissor();
        if (placed.isEmpty()) {
            drawEmptyState(g, graph, x0, y0, bodyW(), bodyH());
        }
        drawFooter(g);
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (hovered != null && !dragging) {
            g.setTooltipForNextFrame(font, NodeTooltip.lines(font, graph, hovered.node()), mouseX, mouseY);
        } else if (hoveredTab >= 0) {
            Identifier id = tabs.get(hoveredTab);
            List<Component> lines = new ArrayList<>();
            lines.add(ClientTooltips.disciplineName(graph, id));
            coreBranchCategoryKey(id).ifPresent(catKey ->
                    lines.add(Component.translatable(catKey).withStyle(ChatFormatting.GOLD)));
            boolean open = isDisciplineOpen(graph, id);
            boolean homeLoaded = isHomeModLoaded(graph, id);
            lines.add(counts(graph, id));
            if (!open) {
                lines.add(Component.translatable(KEY + "addon_unloaded").withStyle(ChatFormatting.RED));
                graph.discipline(id).ifPresent(d ->
                        lines.add(Component.translatable(KEY + "home_unloaded", d.homeMod()).withStyle(ChatFormatting.DARK_GRAY)));
            } else if (!homeLoaded) {
                graph.discipline(id).ifPresent(d ->
                        lines.add(Component.translatable(KEY + "partial_addon", d.homeMod()).withStyle(ChatFormatting.YELLOW)));
            } else {
                graph.discipline(id).ifPresent(d ->
                        lines.add(Component.translatable(KEY + "home", d.homeMod()).withColor(0xFF808080)));
            }
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    private void drawEmptyState(GuiGraphicsExtractor g, LoreGraph graph, int x0, int y0, int w, int h) {
        int cx = x0 + w / 2;
        int cy = y0 + h / 2;
        int maxTextW = Math.max(60, w - 24);
        if (current == null) {
            g.centeredText(font, LoreGui.fit(font, Component.translatable(KEY + "empty"), maxTextW), cx, cy - 4, 0xFFB0B0B0);
            return;
        }
        boolean open = isDisciplineOpen(graph, current);
        Component titleLine = Component.translatable(KEY + "empty_discipline", ClientTooltips.disciplineName(graph, current));
        g.centeredText(font, LoreGui.fit(font, titleLine, maxTextW), cx, cy - 18, 0xFFD0D4D8);
        if (!open) {
            String modId = graph.discipline(current).map(Discipline::homeMod).orElse("unknown");
            Component badge = Component.translatable(KEY + "addon_unloaded");
            g.centeredText(font, LoreGui.fit(font, badge, maxTextW), cx, cy - 4, 0xFFE57368);
            Component detail = Component.translatable(KEY + "addon_unloaded_detail", modId);
            g.centeredText(font, LoreGui.fit(font, detail, maxTextW), cx, cy + 10, 0xFF9AA0A6);
            Component coreBranches = Component.translatable(KEY + "core_branches_hint");
            g.centeredText(font, LoreGui.fit(font, coreBranches, maxTextW), cx, cy + 24, 0xFFD4B254);
        }
    }

    private int tabsAvailableWidth() {
        int reservedRight = hubButton != null && hubButton.visible ? hubButton.getWidth() + 8 : 0;
        return Math.max(48, width - SIDE * 2 - reservedRight);
    }

    private int tabStep() {
        if (tabs.isEmpty()) {
            return TAB + 2;
        }
        return Math.max(12, Math.min(TAB + 2, tabsAvailableWidth() / tabs.size()));
    }

    /** @return the hovered tab, or -1 */
    private int drawTabs(GuiGraphicsExtractor g, LoreGraph graph, int mouseX, int mouseY) {
        int step = tabStep();
        int size = Math.min(TAB, step - 2);
        int hovered = -1;
        for (int i = 0; i < tabs.size(); i++) {
            Identifier id = tabs.get(i);
            Optional<Discipline> discipline = graph.discipline(id);
            int rgb = discipline.map(Discipline::color).orElse(0x808080);
            boolean open = isDisciplineOpen(graph, id);
            int x = SIDE + i * step;
            boolean selected = id.equals(current);
            boolean hover = LoreGui.inside(mouseX, mouseY, x, TAB_Y, size, size);
            if (hover) {
                hovered = i;
            }
            int bg = !open ? 0xB014171A : (selected ? LoreColors.argb(LoreColors.shade(rgb)) : 0xC0202428);
            g.fill(x, TAB_Y, x + size, TAB_Y + size, bg);
            int outlineColor = selected ? 0xFFFFFFFF : (hover ? 0xFFD4B254 : (open ? FRAME : 0xFF282C30));
            g.outline(x, TAB_Y, size, size, outlineColor);
            int barColor = open ? LoreColors.argb(discipline.map(LoreColors::onDark).orElse(rgb)) : 0xFF4A4E54;
            g.fill(x + 1, TAB_Y + size - 3, x + size - 1, TAB_Y + size - 1, barColor);
            discipline.flatMap(d -> BuiltInRegistries.ITEM.getOptional(d.icon())).ifPresent(item -> {
                if (size >= 18) {
                    g.item(new ItemStack(item), x + (size - 16) / 2, TAB_Y + 1);
                }
            });
            if (!open || graph.inDiscipline(id).isEmpty()) {
                g.fill(x + 1, TAB_Y + 1, x + size - 1, TAB_Y + size - 1, 0xA80C0E10);
                if (size >= 14) {
                    LoreGui.lock(g, x + size - 8, TAB_Y + 2, 0xFF8A8E94);
                }
            }
        }
        return hovered;
    }

    private void drawHeader(GuiGraphicsExtractor g, LoreGraph graph) {
        int maxW = Math.max(40, width - SIDE * 2);
        if (current == null) {
            g.text(font, LoreGui.fit(font, title, maxW), SIDE, HEADER_Y, 0xFFFFFFFF, true);
            return;
        }
        MutableComponent headerLine = Component.empty().append(ClientTooltips.disciplineName(graph, current));
        coreBranchCategoryKey(current).ifPresent(catKey ->
                headerLine.append(Component.literal(" · ").withColor(0xFF80868C))
                        .append(Component.translatable(catKey).withColor(0xFFD4B254)));
        Component fittedName = LoreGui.fit(font, headerLine, maxW / 2);
        g.text(font, fittedName, SIDE, HEADER_Y, 0xFFFFFFFF, true);
        int nextX = SIDE + font.width(fittedName) + 8;
        int remaining = width - SIDE - nextX;
        if (remaining > 24) {
            MutableComponent statusPart = Component.empty().append(counts(graph, current));
            if (!isDisciplineOpen(graph, current)) {
                statusPart.append(Component.literal(" | ").withColor(0xFF606468))
                        .append(Component.translatable(KEY + "addon_unloaded").withColor(0xFFE57368));
            } else if (!isHomeModLoaded(graph, current)) {
                graph.discipline(current).ifPresent(d ->
                        statusPart.append(Component.literal(" | ").withColor(0xFF606468))
                                .append(Component.translatable(KEY + "partial_addon", d.homeMod()).withColor(0xFFD4B254)));
            }
            g.text(font, LoreGui.fit(font, statusPart, remaining), nextX, HEADER_Y, 0xFFB0B0B0, true);
        }
    }

    private static Component counts(LoreGraph graph, Identifier discipline) {
        int self = 0;
        int others = 0;
        List<LoreGraph.Node> nodes = graph.inDiscipline(discipline);
        for (LoreGraph.Node node : nodes) {
            if (ClientKnowledge.knowsSelf(node.id())) {
                self++;
            } else if (ClientKnowledge.knows(node.id())) {
                others++;
            }
        }
        return others > 0 ? Component.translatable(KEY + "count.team", self, nodes.size(), others)
                : Component.translatable(KEY + "count", self, nodes.size());
    }

    private void drawEdge(GuiGraphicsExtractor g, LoreGraph graph, Edge edge, int ox, int oy, boolean highlighted) {
        Placed from = edge.from();
        Placed to = edge.to();
        int ax = ox + from.x() + NODE_W;
        int ay = oy + from.y() + NODE_H / 2;
        int bx = ox + to.x();
        int by = oy + to.y() + NODE_H / 2;
        int rgb = graph.discipline(from.node().discipline()).map(LoreColors::onDark).orElse(0x808080);
        int alpha = highlighted || ClientKnowledge.knows(from.node().id()) ? 0xFF000000 : 0x98000000;
        int color = highlighted ? 0xFFFFFFFF : (alpha | (rgb & 0xFFFFFF));
        int mid = bx - (COL_W - NODE_W) / 2;
        if (mid <= ax) {
            mid = (ax + bx) / 2;
        }
        if (edge.any()) {
            LoreGui.dashedHorizontal(g, ax, mid, ay, color);
            LoreGui.dashedVertical(g, mid, ay, by + 1, color);
            LoreGui.dashedHorizontal(g, mid, bx - 2, by, color);
        } else {
            g.fill(ax, ay, mid + 1, ay + 1, color);
            g.fill(mid, Math.min(ay, by), mid + 1, Math.max(ay, by) + 1, color);
            g.fill(mid, by, bx - 2, by + 1, color);
        }
        g.fill(bx - 3, by - 2, bx - 2, by + 3, color);
        g.fill(bx - 2, by - 1, bx - 1, by + 2, color);
    }

    private void drawNode(GuiGraphicsExtractor g, LoreGraph graph, Placed p, int x, int y, boolean hover) {
        Optional<Discipline> discipline = graph.discipline(p.node().discipline());
        int rgb = discipline.map(Discipline::color).orElse(0x808080);
        int border = LoreColors.argb(discipline.map(LoreColors::onDark).orElse(rgb));
        NodeTooltip.State state = NodeTooltip.state(p.node());
        int fill = switch (state) {
            case SELF, OTHERS -> LoreColors.argb(rgb);
            case AVAILABLE -> 0xFFFFFFFF;
            case MISSING -> LoreGui.MISSING_FILL;
        };
        g.fill(x + 1, y + 1, x + NODE_W + 1, y + NODE_H + 1, 0x55000000);
        g.fill(x, y, x + NODE_W, y + NODE_H, fill);
        LoreGui.outline(g, x, y, NODE_W, NODE_H, hover ? 0xFFFFFFFF : border, p.visitor());
        if (!p.visitor()) {
            g.outline(x + 1, y + 1, NODE_W - 2, NODE_H - 2, hover ? 0xFFFFFFFF : border);
        }
        int textRoom = NODE_W - 8;
        if (state == NodeTooltip.State.OTHERS) {
            int mx = x + NODE_W - 8;
            g.fill(mx, y + 3, mx + 5, y + 8, 0xFFFFFFFF);
            g.fill(mx + 1, y + 4, mx + 4, y + 7, LoreColors.argb(LoreColors.shade(rgb)));
            textRoom -= 7;
        } else if (state == NodeTooltip.State.MISSING && !p.visitor()) {
            LoreGui.lock(g, x + NODE_W - 10, y + (NODE_H - 9) / 2, 0xFFCCCCCC);
            textRoom -= 10;
        }
        Component label = NodeTooltip.name(p.node());
        if (p.visitor()) {
            label = Component.literal("[" + discipline.map(Discipline::code).orElse("?") + "] ").append(label);
        }
        g.text(font, LoreGui.fit(font, label, textRoom), x + 4, y + (NODE_H - 8) / 2, LoreColors.textOn(fill & 0xFFFFFF), false);
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int y = height - FOOTER + 4;
        int x = SIDE;
        int maxRight = width - SIDE;
        x = legend(g, x, y, 0xFF4FAF4F, false, Component.translatable(KEY + "legend.self"));
        x = legend(g, x, y, 0xFF4FAF4F, true, Component.translatable(KEY + "legend.team"));
        x = legend(g, x, y, 0xFFFFFFFF, false, Component.translatable(KEY + "legend.available"));
        x = legend(g, x, y, LoreGui.MISSING_FILL, false, Component.translatable(KEY + "legend.missing"));
        Component any = Component.translatable(KEY + "legend.any");
        if (x + 15 + font.width(any) <= maxRight) {
            LoreGui.dashedHorizontal(g, x, x + 12, y + 4, 0xFFB0B0B0);
            x += 15;
            g.text(font, any, x, y, 0xFFB0B0B0, false);
            x += font.width(any) + 10;
        }
        Component hint = Component.translatable(KEY + "hint");
        int hintX = maxRight - font.width(hint);
        if (hintX > x) {
            g.text(font, hint, hintX, y, 0xFF808080, false);
        } else if (maxRight - x > 36) {
            Component fittedHint = LoreGui.fit(font, hint, maxRight - x);
            g.text(font, fittedHint, maxRight - font.width(fittedHint), y, 0xFF808080, false);
        }
    }

    private int legend(GuiGraphicsExtractor g, int x, int y, int fill, boolean team, Component label) {
        int itemW = 12 + font.width(label);
        if (x + itemW > width - SIDE) {
            return x;
        }
        g.fill(x, y, x + 9, y + 9, 0xFFB0B0B0);
        g.fill(x + 1, y + 1, x + 8, y + 8, fill);
        if (team) {
            g.fill(x + 5, y + 1, x + 8, y + 4, 0xFFFFFFFF);
        }
        g.text(font, label, x + 12, y + 1, 0xFFB0B0B0, false);
        return x + 12 + font.width(label) + 10;
    }

    // ---- input ----

    private int tabAt(double mouseX, double mouseY) {
        int step = tabStep();
        int size = Math.min(TAB, step - 2);
        for (int i = 0; i < tabs.size(); i++) {
            if (LoreGui.inside(mouseX, mouseY, SIDE + i * step, TAB_Y, size, size)) {
                return i;
            }
        }
        return -1;
    }

    private Placed nodeAt(double mouseX, double mouseY) {
        int ox = SIDE - (int) scrollX;
        int oy = TOP - (int) scrollY;
        for (Placed p : placed) {
            if (LoreGui.inside(mouseX, mouseY, ox + p.x(), oy + p.y(), NODE_W, NODE_H)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int tab = tabAt(event.x(), event.y());
            if (tab >= 0) {
                select(tabs.get(tab));
                return true;
            }
            if (LoreGui.inside(event.x(), event.y(), SIDE, TOP, bodyW(), bodyH())) {
                Placed p = nodeAt(event.x(), event.y());
                if (p != null && p.visitor()) {
                    jumpTo(p.node());
                    return true;
                }
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            scrollX -= dx;
            scrollY -= dy;
            clampScroll();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && event.button() == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollXAmount, double scrollYAmount) {
        if (minecraft.hasShiftDown()) {
            scrollX -= scrollYAmount * ROW_H;
        } else {
            scrollX -= scrollXAmount * ROW_H;
            scrollY -= scrollYAmount * ROW_H;
        }
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (ClientLore.TREE_KEY.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
            cycle(event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
            return true;
        }
        return super.keyPressed(event);
    }
}

