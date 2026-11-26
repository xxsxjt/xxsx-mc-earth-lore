package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Logic half of earth_lore_any_alt and the /lore validate summary. */
class ReachabilityTest {
    private static Map<String, NodeDef> map(NodeDef... nodes) {
        Map<String, NodeDef> out = new TreeMap<>();
        for (NodeDef node : nodes) {
            out.put(node.id(), node);
        }
        return out;
    }

    @Test
    void alternativeNodeCarriesAnyGroupWhenForeignModMissing() {
        var accepted = map(
                new NodeDef("x:xua/alt", "earth_lore:xua", 1, Gate.THEORY, List.of(), List.of(), false),
                new NodeDef("x:xua/top", "earth_lore:xua", 2, Gate.HARD,
                        List.of(new Prereq.Any(List.of("b:geo/missing", "x:xua/alt"))), List.of(), false));
        var report = Reachability.compute(accepted);
        assertTrue(report.learnable().contains("x:xua/top"));
        assertEquals(1, report.altUsed());
        assertTrue(report.unreachable().isEmpty());
    }

    @Test
    void rootsAreLearnableAndChainsFollow() {
        var accepted = map(
                new NodeDef("a:geo/basics", "earth_lore:geo", 0, Gate.ROOT, List.of(), List.of(), false),
                new NodeDef("a:geo/next", "earth_lore:geo", 1, Gate.SOFT,
                        List.of(new Prereq.All("a:geo/basics")), List.of(), false),
                new NodeDef("a:geo/orphan", "earth_lore:geo", 2, Gate.SOFT,
                        List.of(new Prereq.All("a:geo/unloaded")), List.of(), false));
        var report = Reachability.compute(accepted);
        assertTrue(report.learnable().contains("a:geo/next"));
        assertEquals(List.of("a:geo/orphan"), List.copyOf(report.unreachable()));
    }

    @org.junit.jupiter.api.Test
    void prerequisiteChainCollectsAndOrAncestors() {
        java.util.Map<String, NodeDef> nodes = new java.util.LinkedHashMap<>();
        nodes.put("x:root", new NodeDef("x:root", "x:d", 0, Gate.HARD, java.util.List.of(), java.util.List.of(), false));
        nodes.put("x:alt_a", new NodeDef("x:alt_a", "x:d", 1, Gate.HARD, java.util.List.of(new Prereq.All("x:root")), java.util.List.of(), false));
        nodes.put("x:alt_b", new NodeDef("x:alt_b", "x:d", 1, Gate.HARD, java.util.List.of(new Prereq.All("x:root")), java.util.List.of(), false));
        nodes.put("x:talisman", new NodeDef("x:talisman", "x:d", 2, Gate.HARD,
                java.util.List.of(new Prereq.Any(java.util.List.of("x:alt_a", "x:alt_b"))), java.util.List.of(), false));
        nodes.put("x:unrelated", new NodeDef("x:unrelated", "x:d", 0, Gate.HARD, java.util.List.of(), java.util.List.of(), false));

        java.util.Set<String> chain = Reachability.prerequisiteChain(nodes, "x:talisman");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of("x:talisman", "x:alt_a", "x:alt_b", "x:root"), chain);
        org.junit.jupiter.api.Assertions.assertFalse(chain.contains("x:unrelated"));
        org.junit.jupiter.api.Assertions.assertTrue(Reachability.prerequisiteChain(nodes, "x:missing").isEmpty());
    }
}
