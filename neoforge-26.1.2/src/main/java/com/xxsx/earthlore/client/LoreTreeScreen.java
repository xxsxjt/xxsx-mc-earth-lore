package com.xxsx.earthlore.client;

import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.graph.LoreGraph;
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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
    private static final int COL_W = 112;
    private static final int NODE_W = 96;
    private static final int NODE_H = 18;
    private static final int ROW_H = 24;
    private static final int TITLE_H = 14;
    private static final int PAD = 8;
    private static final int BODY_FILL = 0xD0101418;
    private static final int FRAME = 0xFF3A3F46;

    private static Identifier lastDiscipline;

    private record Placed(LoreGraph.Node node, int x, int y, boolean visitor) {
    }

    private record Edge(Placed from, Placed to, boolean any) {
    }

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
        super(Component.translatable(KEY + "title"));
    }

    public LoreTreeScreen(Identifier initialDiscipline) {
        this();
        this.current = initialDiscipline;
        lastDiscipline = initialDiscipline;
    }

    @Override
    protected void init() {
        LoreGraph graph = ClientKnowledge.graph();
        tabs = visibleDisciplines(graph);
        if (current == null || !tabs.contains(current)) {
            current = lastDiscipline != null && tabs.contains(lastDiscipline) ? lastDiscipline : firstWithNodes(graph);
        }
        rebuild();
    }

    private static List<Identifier> visibleDisciplines(LoreGraph graph) {
        return graph.disciplineOrder().stream()
                .filter(id -> !graph.inDiscipline(id).isEmpty()
                        || graph.discipline(id).map(d -> ModList.get().isLoaded(d.homeMod())).orElse(false))
                .toList();
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
        lastDiscipline = discipline;
        scrollX = 0;
        scrollY = 0;
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
        int at = Math.max(0, tabs.indexOf(current));
        select(tabs.get(Math.floorMod(at + step, tabs.size())));
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
        for (Edge edge : edges) {
            drawEdge(g, graph, edge, ox, oy);
        }
        boolean inBody = LoreGui.inside(mouseX, mouseY, x0, y0, x1 - x0, y1 - y0);
        for (Placed p : placed) {
            boolean hover = inBody && LoreGui.inside(mouseX, mouseY, ox + p.x(), oy + p.y(), NODE_W, NODE_H);
            if (hover) {
                hovered = p;
            }
            drawNode(g, graph, p, ox + p.x(), oy + p.y(), hover);
        }
        g.disableScissor();
        if (placed.isEmpty()) {
            Component message = current == null ? Component.translatable(KEY + "empty")
                    : Component.translatable(KEY + "empty_discipline", ClientTooltips.disciplineName(graph, current));
            g.centeredText(font, message, width / 2, y0 + bodyH() / 2 - 4, 0xFFB0B0B0);
        }
        drawFooter(g);
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (hovered != null && !dragging) {
            g.setTooltipForNextFrame(font, NodeTooltip.lines(font, graph, hovered.node()), mouseX, mouseY);
        } else if (hoveredTab >= 0) {
            Identifier id = tabs.get(hoveredTab);
            List<Component> lines = new ArrayList<>();
            lines.add(ClientTooltips.disciplineName(graph, id));
            lines.add(counts(graph, id));
            graph.discipline(id).ifPresent(d -> lines.add(Component.translatable(KEY + "home", d.homeMod()).withColor(0xFF808080)));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    private int tabStep() {
        if (tabs.isEmpty()) {
            return TAB + 2;
        }
        return Math.max(12, Math.min(TAB + 2, (width - SIDE * 2) / tabs.size()));
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
            int x = SIDE + i * step;
            boolean selected = id.equals(current);
            boolean hover = LoreGui.inside(mouseX, mouseY, x, TAB_Y, size, size);
            if (hover) {
                hovered = i;
            }
            g.fill(x, TAB_Y, x + size, TAB_Y + size, selected ? LoreColors.argb(LoreColors.shade(rgb)) : 0xC0202428);
            g.outline(x, TAB_Y, size, size, selected || hover ? 0xFFFFFFFF : FRAME);
            g.fill(x + 1, TAB_Y + size - 3, x + size - 1, TAB_Y + size - 1, LoreColors.argb(discipline.map(LoreColors::onDark).orElse(rgb)));
            discipline.flatMap(d -> BuiltInRegistries.ITEM.getOptional(d.icon())).ifPresent(item -> {
                if (size >= 18) {
                    g.item(new ItemStack(item), x + (size - 16) / 2, TAB_Y + 1);
                }
            });
            if (graph.inDiscipline(id).isEmpty()) {
                g.fill(x + 1, TAB_Y + 1, x + size - 1, TAB_Y + size - 1, 0x90000000);
            }
        }
        return hovered;
    }

    private void drawHeader(GuiGraphicsExtractor g, LoreGraph graph) {
        if (current == null) {
            g.text(font, title, SIDE, HEADER_Y, 0xFFFFFFFF, true);
            return;
        }
        Component name = ClientTooltips.disciplineName(graph, current);
        g.text(font, name, SIDE, HEADER_Y, 0xFFFFFFFF, true);
        g.text(font, counts(graph, current), SIDE + font.width(name) + 8, HEADER_Y, 0xFFB0B0B0, true);
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

    private void drawEdge(GuiGraphicsExtractor g, LoreGraph graph, Edge edge, int ox, int oy) {
        Placed from = edge.from();
        Placed to = edge.to();
        int ax = ox + from.x() + NODE_W;
        int ay = oy + from.y() + NODE_H / 2;
        int bx = ox + to.x();
        int by = oy + to.y() + NODE_H / 2;
        int rgb = graph.discipline(from.node().discipline()).map(LoreColors::onDark).orElse(0x808080);
        int alpha = ClientKnowledge.knows(from.node().id()) ? 0xFF000000 : 0x80000000;
        int color = alpha | (rgb & 0xFFFFFF);
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
        }
        Component label = NodeTooltip.name(p.node());
        if (p.visitor()) {
            label = Component.literal("[" + discipline.map(Discipline::code).orElse("?") + "] ").append(label);
        }
        g.text(font, LoreGui.fit(font, label, textRoom), x + 4, y + 5, LoreColors.textOn(fill & 0xFFFFFF), false);
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int y = height - FOOTER + 4;
        int x = SIDE;
        x = legend(g, x, y, 0xFF4FAF4F, false, Component.translatable(KEY + "legend.self"));
        x = legend(g, x, y, 0xFF4FAF4F, true, Component.translatable(KEY + "legend.team"));
        x = legend(g, x, y, 0xFFFFFFFF, false, Component.translatable(KEY + "legend.available"));
        x = legend(g, x, y, LoreGui.MISSING_FILL, false, Component.translatable(KEY + "legend.missing"));
        LoreGui.dashedHorizontal(g, x, x + 12, y + 4, 0xFFB0B0B0);
        x += 15;
        Component any = Component.translatable(KEY + "legend.any");
        g.text(font, any, x, y, 0xFFB0B0B0, false);
        x += font.width(any) + 10;
        Component hint = Component.translatable(KEY + "hint");
        int hintX = width - SIDE - font.width(hint);
        if (hintX > x) {
            g.text(font, hint, hintX, y, 0xFF808080, false);
        }
    }

    private int legend(GuiGraphicsExtractor g, int x, int y, int fill, boolean team, Component label) {
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

