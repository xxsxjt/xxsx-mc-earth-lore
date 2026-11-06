package com.xxsx.earthlore.core;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Install-combination check (10 册 A-019): which loaded nodes can actually be learned. */
public final class Reachability {
    public record Report(Set<String> learnable, Set<String> unreachable, int altUsed) {
    }

    private Reachability() {
    }

    public static Report compute(Map<String, NodeDef> accepted) {
        Set<String> learnable = new TreeSet<>();
        for (NodeDef node : accepted.values()) {
            if (node.gate() == Gate.ROOT) {
                learnable.add(node.id());
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NodeDef node : accepted.values()) {
                if (!learnable.contains(node.id()) && satisfied(node, learnable::contains)) {
                    learnable.add(node.id());
                    changed = true;
                }
            }
        }
        Set<String> unreachable = new TreeSet<>(accepted.keySet());
        unreachable.removeAll(learnable);
        int altUsed = 0;
        for (NodeDef node : accepted.values()) {
            String ns = node.namespace();
            for (Prereq prereq : node.requires()) {
                if (!(prereq instanceof Prereq.Any group)) {
                    continue;
                }
                boolean hasCross = false;
                boolean crossLoaded = false;
                boolean sameLoaded = false;
                for (String member : group.nodes()) {
                    boolean same = NodeIds.namespace(member).equals(ns);
                    hasCross |= !same;
                    crossLoaded |= !same && accepted.containsKey(member);
                    sameLoaded |= same && accepted.containsKey(member);
                }
                if (hasCross && !crossLoaded && sameLoaded) {
                    altUsed++;
                }
            }
        }
        return new Report(learnable, unreachable, altUsed);
    }

    /** All "all" prerequisites known and every "any" group has at least one known member. */
    public static boolean satisfied(NodeDef node, Predicate<String> known) {
        for (Prereq prereq : node.requires()) {
            if (prereq instanceof Prereq.All required) {
                if (!known.test(required.node())) {
                    return false;
                }
            } else if (prereq.members().stream().noneMatch(known)) {
                return false;
            }
        }
        return true;
    }
}

