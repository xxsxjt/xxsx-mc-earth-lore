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
}

