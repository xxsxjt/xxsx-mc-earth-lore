package com.xxsx.earthlore.gametest;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.data.ItemReq;
import com.xxsx.earthlore.data.KnowledgeNode;
import com.xxsx.earthlore.data.LoreCondition;
import com.xxsx.earthlore.data.LoreIngredient;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.data.ResearchCost;
import com.xxsx.earthlore.data.UnlockRef;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Knowledge graph of the GameTests, built in code so the framework can be tested without any subject mod. The
 * test environment installs it on setup; teardown restores the data-pack graph. All nodes sit in GEO, whose
 * discipline record ships with earth_lore.
 */
final class LoreFixture {
    static final Identifier GEO = EarthLore.id("geo");
    static final Identifier ROOT = EarthLore.id("test/root");
    static final Identifier BASIC = EarthLore.id("test/basic");
    static final Identifier ADVANCED = EarthLore.id("test/advanced");
    static final Identifier SOFT = EarthLore.id("test/soft");
    static final Identifier CAPSTONE = EarthLore.id("test/capstone");
    static final Identifier HARD = EarthLore.id("test/hard");
    static final Identifier ANY = EarthLore.id("test/any_alt");
    /** Member of ANY's "any" group that belongs to a mod that is not installed. */
    static final Identifier ABSENT = Identifier.fromNamespaceAndPath("earth_lore_absent", "missing");
    static final Identifier AETHER = EarthLore.id("test/aether");
    static final Identifier GHOST = EarthLore.id("test/ghost");
    static final Identifier MIGRATED = EarthLore.id("test/migrated");
    static final Identifier TEACH_A = EarthLore.id("test/teach_a");
    static final Identifier TEACH_B = EarthLore.id("test/teach_b");
    /** Only used as a carrier-form node filter; never loaded. */
    static final Identifier FILTERED = EarthLore.id("test/filtered");

    static final String SOFT_ITEM = "minecraft:wooden_shovel";
    static final String HARD_ITEM = "minecraft:wooden_hoe";
    static final String HARD_SMELT = "minecraft:charcoal";

    private LoreFixture() {
    }

    /** @param withGhost whether GHOST is loaded; earth_lore_unloaded_keep toggles it to simulate removing a mod */
    static void install(boolean withGhost) {
        Map<Identifier, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(ROOT, node(0, "root", List.of(), List.of(), List.of(), ResearchCost.NONE));
        nodes.put(BASIC, node(1, "theory", all(ROOT), List.of(), List.of(), paper(2, 40)));
        nodes.put(ADVANCED, node(2, "theory", all(BASIC), List.of(), List.of(), paper(1, 40)));
        nodes.put(SOFT, node(1, "soft", all(ROOT), List.of(), List.of(item(SOFT_ITEM)), paper(1, 40)));
        nodes.put(CAPSTONE, node(2, "cap", all(SOFT), List.of(),
                List.of(new UnlockRef(UnlockRef.Kind.CAPSTONE, GEO.toString())), paper(1, 40)));
        nodes.put(HARD, node(1, "hard", all(ROOT), List.of(), List.of(item(HARD_ITEM), item(HARD_SMELT)), paper(1, 40)));
        nodes.put(ANY, node(2, "theory", List.of(new Prereq.Any(List.of(ABSENT.toString(), BASIC.toString()))),
                List.of(), List.of(), paper(1, 40)));
        nodes.put(AETHER, node(1, "theory", all(ROOT),
                List.of(new ItemReq(Optional.empty(), Optional.empty(), Optional.of(LoreCondition.parse("aether>=10")))),
                List.of(), paper(1, 400)));
        if (withGhost) {
            nodes.put(GHOST, node(1, "theory", all(ROOT), List.of(), List.of(), paper(1, 40)));
        }
        nodes.put(MIGRATED, node(1, "theory", all(ROOT), List.of(), List.of(), paper(1, 40)));
        nodes.put(TEACH_A, node(1, "theory", all(ROOT), List.of(), List.of(), paper(1, 40)));
        nodes.put(TEACH_B, node(1, "theory", all(ROOT), List.of(), List.of(), paper(1, 40)));
        KnowledgeService.installForTest(nodes);
    }

    private static KnowledgeNode node(int tier, String gate, List<Prereq> requires, List<ItemReq> itemRequires,
                                      List<UnlockRef> unlocks, ResearchCost research) {
        return new KnowledgeNode(Optional.of(GEO), Optional.of(tier), Optional.of(gate), requires, itemRequires, unlocks,
                List.of(LoreText.FORM_NOTES), List.of(), research, List.of(), Optional.empty(), Optional.empty());
    }

    private static List<Prereq> all(Identifier parent) {
        return List.of(new Prereq.All(parent.toString()));
    }

    private static UnlockRef item(String id) {
        return new UnlockRef(UnlockRef.Kind.ITEM, id);
    }

    private static ResearchCost paper(int count, int ticks) {
        return new ResearchCost(List.of(new ResearchCost.Material(LoreIngredient.ofItem(Identifier.withDefaultNamespace("paper")), count)),
                ticks);
    }
}

