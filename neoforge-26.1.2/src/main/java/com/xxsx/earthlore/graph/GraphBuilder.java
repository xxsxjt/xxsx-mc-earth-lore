package com.xxsx.earthlore.graph;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.core.Gate;
import com.xxsx.earthlore.core.GraphValidator;
import com.xxsx.earthlore.core.NodeDef;
import com.xxsx.earthlore.core.Reachability;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.ItemReq;
import com.xxsx.earthlore.data.KnowledgeNode;
import com.xxsx.earthlore.data.LoreCondition;
import com.xxsx.earthlore.data.ResearchCost;
import com.xxsx.earthlore.data.UnlockRef;
import com.xxsx.earthlore.registry.LoreRegistries;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/** Reads the registries, runs the A-002 checks and the A-019 summary. */
public final class GraphBuilder {
    public record Report(LoreGraph graph, Map<String, Integer> rejectedByCode, Reachability.Report reach) {
        public String summaryLine() {
            return "[earth_lore] validate nodes=" + graph.nodes().size()
                    + " learnable=" + reach.learnable().size()
                    + " rejected=" + graph.rejected().size()
                    + " unreachable=" + reach.unreachable().size()
                    + " alt_used=" + reach.altUsed();
        }
    }

    private GraphBuilder() {
    }

    public static Map<Identifier, Discipline> disciplines(RegistryAccess access) {
        Map<Identifier, Discipline> out = new LinkedHashMap<>();
        access.lookup(LoreRegistries.DISCIPLINE).ifPresent(registry -> registry.entrySet()
                .forEach(entry -> out.put(entry.getKey().identifier(), entry.getValue())));
        return out;
    }

    public static Map<Identifier, KnowledgeNode> rawNodes(RegistryAccess access) {
        Map<Identifier, KnowledgeNode> out = new TreeMap<>();
        access.lookup(LoreRegistries.KNOWLEDGE_NODE).ifPresent(registry -> registry.entrySet()
                .forEach(entry -> out.put(entry.getKey().identifier(), entry.getValue())));
        return out;
    }

    /** Server build: validates every node and logs one line per rejection plus the summary. */
    public static Report server(MinecraftServer server) {
        RegistryAccess access = server.registryAccess();
        Map<Identifier, Discipline> disciplines = disciplines(access);
        checkDisciplines(disciplines);
        Map<Identifier, KnowledgeNode> raw = rawNodes(access);
        Predicate<Identifier> recipeExists = id -> server.getRecipeManager()
                .byKey(ResourceKey.create(Registries.RECIPE, id)).isPresent();
        Map<Identifier, NodeDef> defs = new HashMap<>();
        for (Map.Entry<Identifier, KnowledgeNode> entry : raw.entrySet()) {
            defs.put(entry.getKey(), toDef(entry.getKey(), entry.getValue(), disciplines,
                    !referencesExist(entry.getValue(), disciplines, recipeExists)));
        }
        Set<String> disciplineIds = new java.util.HashSet<>();
        disciplines.keySet().forEach(id -> disciplineIds.add(id.toString()));
        GraphValidator.Result result = GraphValidator.validate(defs.values(), disciplineIds, GraphBuilder::dependsOn);
        Map<Identifier, String> rejected = new TreeMap<>();
        result.rejected().forEach((id, code) -> {
            rejected.put(Identifier.parse(id), code);
            EarthLore.LOGGER.warn("[earth_lore] rejected node {} {}", id, code);
        });
        LoreGraph graph = LoreGraph.build(disciplines, raw, defs, rejected);
        Reachability.Report reach = Reachability.compute(result.accepted());
        for (String id : reach.unreachable()) {
            EarthLore.LOGGER.error("[earth_lore] {} {}", FailCodes.UNREACHABLE, id);
        }
        Report report = new Report(graph, result.rejectedByCode(), reach);
        if (!graph.rejected().isEmpty()) {
            EarthLore.LOGGER.warn("[earth_lore] rejected by code: {}", report.rejectedByCode());
        }
        EarthLore.LOGGER.info(report.summaryLine());
        return report;
    }

    /** Client build: trusts the server's rejection list. */
    public static LoreGraph client(RegistryAccess access, Set<Identifier> rejectedIds) {
        Map<Identifier, Discipline> disciplines = disciplines(access);
        Map<Identifier, KnowledgeNode> raw = rawNodes(access);
        Map<Identifier, NodeDef> defs = new HashMap<>();
        raw.forEach((id, node) -> defs.put(id, toDef(id, node, disciplines, false)));
        Map<Identifier, String> rejected = new TreeMap<>();
        rejectedIds.forEach(id -> rejected.put(id, "rejected"));
        return LoreGraph.build(disciplines, raw, defs, rejected);
    }

    public static NodeDef toDef(Identifier id, KnowledgeNode node, Map<Identifier, Discipline> disciplines,
                                boolean badReference) {
        String discipline = node.discipline().map(Identifier::toString).orElse(null);
        Integer tier = node.tier().orElse(null);
        Gate gate = node.gate().flatMap(Gate::parse).orElse(null);
        List<String> keys = node.unlocks().stream()
                .filter(unlock -> unlock.kind() != UnlockRef.Kind.INVALID)
                .map(unlock -> unlock.key(id.getNamespace()))
                .toList();
        return new NodeDef(id.toString(), discipline, tier, gate, node.requires(), keys, badReference);
    }

    private static boolean referencesExist(KnowledgeNode node, Map<Identifier, Discipline> disciplines,
                                           Predicate<Identifier> recipeExists) {
        for (UnlockRef unlock : node.unlocks()) {
            Identifier target = Identifier.tryParse(unlock.value());
            boolean ok = switch (unlock.kind()) {
                case ITEM, READOUT -> target != null && BuiltInRegistries.ITEM.containsKey(target);
                case BLOCK, YIELD -> target != null && BuiltInRegistries.BLOCK.containsKey(target);
                case RECIPE -> target != null && recipeExists.test(target);
                case RECIPE_TYPE -> target != null && BuiltInRegistries.RECIPE_TYPE.containsKey(target);
                case ABILITY -> !unlock.value().isBlank();
                case CAPSTONE -> target != null && disciplines.containsKey(target)
                        && node.discipline().map(target::equals).orElse(false);
                case INVALID -> false;
            };
            if (!ok) {
                return false;
            }
        }
        ResearchCost research = node.research();
        for (ResearchCost.Material material : research.items()) {
            if (!material.ingredient().exists()) {
                return false;
            }
        }
        for (ItemReq req : node.itemRequires()) {
            if (req.hold().isPresent() && !req.hold().get().exists()) {
                return false;
            }
            if (req.made().isPresent() && !BuiltInRegistries.ITEM.containsKey(req.made().get())) {
                return false;
            }
            if (req.cond().isPresent() && req.cond().get().kind() == LoreCondition.Kind.INVALID) {
                return false;
            }
        }
        return true;
    }

    private static void checkDisciplines(Map<Identifier, Discipline> disciplines) {
        Map<String, Identifier> codes = new HashMap<>();
        for (Map.Entry<Identifier, Discipline> entry : disciplines.entrySet()) {
            Discipline discipline = entry.getValue();
            Identifier previous = codes.put(discipline.code(), entry.getKey());
            if (!discipline.validCode() || previous != null) {
                throw new IllegalStateException("[earth_lore] " + FailCodes.BAD_DISCIPLINE + " " + entry.getKey()
                        + " code=" + discipline.code() + (previous != null ? " duplicates " + previous : ""));
            }
        }
    }

    /** True when mod owner is other or declares other as a required dependency (11 册 §5 rule 4, A-014 rule 3). */
    public static boolean dependsOn(String owner, String other) {
        if (owner.equals(other)) {
            return true;
        }
        return ModList.get().getModContainerById(owner)
                .map(container -> container.getModInfo().getDependencies().stream()
                        .anyMatch(dep -> dep.getModId().equals(other) && dep.getType() == IModInfo.DependencyType.REQUIRED))
                .orElse(false);
    }
}

