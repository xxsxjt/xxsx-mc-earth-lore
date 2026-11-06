package com.xxsx.earthlore.graph;

import com.xxsx.earthlore.core.Gate;
import com.xxsx.earthlore.core.NodeDef;
import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.KnowledgeNode;
import com.xxsx.earthlore.data.UnlockRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Immutable view of the loaded knowledge graph. Built once per server start from the data-pack
 * registries (and on the client from the synced registries plus the server's rejection list).
 */
public final class LoreGraph {
    public static final Comparator<Node> STUDY_ORDER = Comparator.comparingInt(Node::tier)
            .thenComparing(node -> node.id().toString());

    public record Node(Identifier id, KnowledgeNode data, Identifier discipline, int tier, Gate gate, NodeDef def) {
        public String namespace() {
            return id.getNamespace();
        }

        public String nameKey() {
            return data.nameKey().orElse("lore." + id.getNamespace() + "." + id.getPath().replace('/', '.'));
        }

        public String descKey() {
            return data.descKey().orElse(nameKey() + ".desc");
        }
    }

    /** Gate nodes for one piece of content; lockedByRejected means a rejected node gated it. */
    public record Gates(List<Node> nodes, boolean lockedByRejected) {
        public static final Gates NONE = new Gates(List.of(), false);

        public boolean isEmpty() {
            return nodes.isEmpty() && !lockedByRejected;
        }

        Gates plus(Gates other) {
            if (other.isEmpty()) {
                return this;
            }
            if (isEmpty()) {
                return other;
            }
            List<Node> merged = new ArrayList<>(nodes);
            for (Node node : other.nodes) {
                if (!merged.contains(node)) {
                    merged.add(node);
                }
            }
            return new Gates(List.copyOf(merged), lockedByRejected || other.lockedByRejected);
        }
    }

    public static final LoreGraph EMPTY = new LoreGraph(Map.of(), List.of(), Map.of(), Map.of());

    private final Map<Identifier, Discipline> disciplines;
    private final List<Identifier> disciplineOrder;
    private final List<Identifier> order;
    private final Map<Identifier, Integer> indexOf = new HashMap<>();
    private final Map<Identifier, Node> nodes = new LinkedHashMap<>();
    private final Map<Identifier, String> rejected;
    private final Set<Identifier> roots = new LinkedHashSet<>();
    private final Map<Identifier, Set<Identifier>> dependents = new HashMap<>();
    private final Map<Identifier, List<Node>> byDiscipline = new HashMap<>();
    private final Map<Item, Node> itemGates = new HashMap<>();
    private final Map<Block, Node> blockGates = new HashMap<>();
    private final Map<Identifier, Node> recipeGates = new HashMap<>();
    private final Map<Identifier, Node> recipeTypeGates = new HashMap<>();
    private final Map<String, Node> abilityGates = new HashMap<>();
    private final Map<Item, Node> readouts = new HashMap<>();
    private final Map<Block, Node> yields = new HashMap<>();
    private final Map<String, Node> capstones = new HashMap<>();
    private final Set<Item> lockedItems = new HashSet<>();
    private final Set<Block> lockedBlocks = new HashSet<>();
    private final Set<Identifier> lockedRecipes = new HashSet<>();
    private final Set<Identifier> lockedRecipeTypes = new HashSet<>();
    private final Set<String> lockedAbilities = new HashSet<>();
    private final int hash;

    private LoreGraph(Map<Identifier, Discipline> disciplines, List<Identifier> order, Map<Identifier, Node> accepted,
                      Map<Identifier, String> rejected) {
        this.disciplines = Collections.unmodifiableMap(new LinkedHashMap<>(disciplines));
        List<Identifier> dOrder = new ArrayList<>(disciplines.keySet());
        dOrder.sort(Comparator.comparingInt((Identifier id) -> disciplines.get(id).order()).thenComparing(Identifier::toString));
        this.disciplineOrder = List.copyOf(dOrder);
        this.order = List.copyOf(order);
        for (int i = 0; i < order.size(); i++) {
            indexOf.put(order.get(i), i);
        }
        this.rejected = Collections.unmodifiableMap(new TreeMap<>(rejected));
        this.nodes.putAll(accepted);
        this.hash = order.hashCode();
    }

    /**
     * @param raw      every node in the registry, keyed by id
     * @param rejected ids rejected by validation (server) or by the server's sync (client)
     */
    public static LoreGraph build(Map<Identifier, Discipline> disciplines, Map<Identifier, KnowledgeNode> raw,
                                  Map<Identifier, NodeDef> defs, Map<Identifier, String> rejected) {
        List<Identifier> order = new ArrayList<>(raw.keySet());
        order.sort(Comparator.comparing(Identifier::toString));
        Map<Identifier, Node> accepted = new LinkedHashMap<>();
        Map<Identifier, String> allRejected = new TreeMap<>(rejected);
        for (Identifier id : order) {
            if (allRejected.containsKey(id)) {
                continue;
            }
            KnowledgeNode data = raw.get(id);
            NodeDef def = defs.get(id);
            Optional<Gate> gate = data.gate().flatMap(Gate::parse);
            if (data.discipline().isEmpty() || data.tier().isEmpty() || gate.isEmpty() || def == null
                    || !disciplines.containsKey(data.discipline().get())) {
                allRejected.put(id, "lore_bad_tier");
                continue;
            }
            accepted.put(id, new Node(id, data, data.discipline().get(), data.tier().get(), gate.get(), def));
        }
        LoreGraph graph = new LoreGraph(disciplines, order, accepted, allRejected);
        graph.index(raw);
        return graph;
    }

    private void index(Map<Identifier, KnowledgeNode> raw) {
        for (Node node : nodes.values()) {
            if (node.gate() == Gate.ROOT) {
                roots.add(node.id());
            }
            byDiscipline.computeIfAbsent(node.discipline(), ignored -> new ArrayList<>()).add(node);
            for (Prereq prereq : node.def().requires()) {
                for (String member : prereq.members()) {
                    Identifier parent = Identifier.tryParse(member);
                    if (parent != null) {
                        dependents.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(node.id());
                    }
                }
            }
            for (UnlockRef unlock : node.data().unlocks()) {
                indexUnlock(node, unlock);
            }
        }
        byDiscipline.values().forEach(list -> list.sort(STUDY_ORDER));
        for (Map.Entry<Identifier, String> entry : rejected.entrySet()) {
            KnowledgeNode data = raw.get(entry.getKey());
            if (data == null) {
                continue;
            }
            for (UnlockRef unlock : data.unlocks()) {
                Identifier target = Identifier.tryParse(unlock.value());
                switch (unlock.kind()) {
                    case ITEM -> item(target).ifPresent(lockedItems::add);
                    case BLOCK -> block(target).ifPresent(lockedBlocks::add);
                    case RECIPE -> {
                        if (target != null) {
                            lockedRecipes.add(target);
                        }
                    }
                    case RECIPE_TYPE -> {
                        if (target != null) {
                            lockedRecipeTypes.add(target);
                        }
                    }
                    case ABILITY -> lockedAbilities.add(unlock.value());
                    default -> {
                    }
                }
            }
        }
    }

    private void indexUnlock(Node node, UnlockRef unlock) {
        Identifier target = Identifier.tryParse(unlock.value());
        switch (unlock.kind()) {
            case ITEM -> item(target).ifPresent(item -> itemGates.put(item, node));
            case BLOCK -> block(target).ifPresent(block -> blockGates.put(block, node));
            case RECIPE -> {
                if (target != null) {
                    recipeGates.put(target, node);
                }
            }
            case RECIPE_TYPE -> {
                if (target != null) {
                    recipeTypeGates.put(target, node);
                }
            }
            case ABILITY -> abilityGates.put(unlock.value(), node);
            case READOUT -> item(target).ifPresent(item -> readouts.put(item, node));
            case YIELD -> block(target).ifPresent(block -> yields.put(block, node));
            case CAPSTONE -> capstones.put(unlock.value() + "@" + node.namespace(), node);
            default -> {
            }
        }
    }

    private static Optional<Item> item(Identifier id) {
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    private static Optional<Block> block(Identifier id) {
        return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
    }

    public int hash() {
        return hash;
    }

    public List<Identifier> order() {
        return order;
    }

    public int index(Identifier id) {
        return indexOf.getOrDefault(id, -1);
    }

    public Map<Identifier, Discipline> disciplines() {
        return disciplines;
    }

    public List<Identifier> disciplineOrder() {
        return disciplineOrder;
    }

    public Optional<Discipline> discipline(Identifier id) {
        return Optional.ofNullable(disciplines.get(id));
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Optional<Node> node(Identifier id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public boolean loaded(Identifier id) {
        return nodes.containsKey(id);
    }

    public Map<Identifier, String> rejected() {
        return rejected;
    }

    public Set<Identifier> roots() {
        return Collections.unmodifiableSet(roots);
    }

    public List<Node> inDiscipline(Identifier discipline) {
        return Collections.unmodifiableList(byDiscipline.getOrDefault(discipline, List.of()));
    }

    /** Learned nodes that (transitively) list the given node as a prerequisite. */
    public Set<Identifier> dependentsClosure(Identifier root) {
        Set<Identifier> out = new LinkedHashSet<>();
        Deque<Identifier> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Identifier next = queue.poll();
            for (Identifier child : dependents.getOrDefault(next, Set.of())) {
                if (out.add(child)) {
                    queue.add(child);
                }
            }
        }
        return out;
    }

    public Optional<Node> capstoneFor(Node gateNode) {
        return Optional.ofNullable(capstones.get(gateNode.discipline() + "@" + gateNode.namespace()));
    }

    public Gates itemGates(Item item) {
        Node node = itemGates.get(item);
        return gates(node, lockedItems.contains(item));
    }

    public Gates blockGates(Block block) {
        Node node = blockGates.get(block);
        return gates(node, lockedBlocks.contains(block));
    }

    public Gates recipeGates(Identifier recipeId, Identifier recipeType) {
        Gates byRecipe = gates(recipeGates.get(recipeId), lockedRecipes.contains(recipeId));
        if (recipeType == null) {
            return byRecipe;
        }
        return byRecipe.plus(gates(recipeTypeGates.get(recipeType), lockedRecipeTypes.contains(recipeType)));
    }

    public Gates abilityGates(String ability) {
        return gates(abilityGates.get(ability), lockedAbilities.contains(ability));
    }

    public Optional<Node> readoutNode(Item tool) {
        return Optional.ofNullable(readouts.get(tool));
    }

    public Optional<Node> yieldNode(Block block) {
        return Optional.ofNullable(yields.get(block));
    }

    public boolean hasRecipeGate(Identifier recipeId) {
        return recipeGates.containsKey(recipeId) || lockedRecipes.contains(recipeId);
    }

    private static Gates gates(Node node, boolean locked) {
        if (node == null && !locked) {
            return Gates.NONE;
        }
        return new Gates(node == null ? List.of() : List.of(node), locked);
    }
}

