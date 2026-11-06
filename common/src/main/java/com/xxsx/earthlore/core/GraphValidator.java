package com.xxsx.earthlore.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Load-time checks for knowledge nodes (10 册 A-002). Each rejected node gets exactly one failure code;
 * successors of rejected nodes are rejected in cascade so gated content never opens silently.
 */
public final class GraphValidator {
    /** Whether {@code other} is a mandatory dependency of {@code owner} (same namespace always counts). */
    @FunctionalInterface
    public interface DependencyRule {
        boolean required(String ownerNamespace, String otherNamespace);
    }

    public record Result(Map<String, NodeDef> accepted, Map<String, String> rejected) {
        public Map<String, Integer> rejectedByCode() {
            Map<String, Integer> counts = new TreeMap<>();
            for (String code : rejected.values()) {
                counts.merge(code, 1, Integer::sum);
            }
            return counts;
        }
    }

    private GraphValidator() {
    }

    public static Result validate(Collection<NodeDef> nodes, Set<String> disciplines, DependencyRule deps) {
        Map<String, NodeDef> all = new TreeMap<>();
        for (NodeDef node : nodes) {
            all.put(node.id(), node);
        }
        Map<String, String> rejected = new TreeMap<>();
        for (NodeDef node : all.values()) {
            String code = staticFailure(node, all, disciplines, deps);
            if (code != null) {
                rejected.put(node.id(), code);
            }
        }
        Map<String, List<String>> owners = new HashMap<>();
        for (NodeDef node : all.values()) {
            for (String key : new LinkedHashSet<>(node.unlockKeys())) {
                owners.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node.id());
            }
        }
        for (List<String> ids : owners.values()) {
            if (ids.size() > 1) {
                for (String id : ids) {
                    rejected.putIfAbsent(id, FailCodes.DUPLICATE_UNLOCK);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = cascade(all, rejected);
            changed |= rejectCycles(all, rejected);
        }
        Map<String, NodeDef> accepted = new TreeMap<>();
        for (NodeDef node : all.values()) {
            if (!rejected.containsKey(node.id())) {
                accepted.put(node.id(), node);
            }
        }
        return new Result(Collections.unmodifiableMap(accepted), Collections.unmodifiableMap(rejected));
    }

    private static String staticFailure(NodeDef node, Map<String, NodeDef> all, Set<String> disciplines,
                                        DependencyRule deps) {
        if (node.discipline() == null || !disciplines.contains(node.discipline())) {
            return FailCodes.BAD_DISCIPLINE;
        }
        if (node.tier() == null || node.tier() < 0 || node.tier() > 15 || node.gate() == null) {
            return FailCodes.BAD_TIER;
        }
        if (node.gate() == Gate.ROOT && node.tier() != 0) {
            return FailCodes.BAD_TIER;
        }
        for (Prereq prereq : node.requires()) {
            if (prereq.members().stream().noneMatch(all::containsKey)) {
                return FailCodes.MISSING_PREREQ;
            }
        }
        for (Prereq prereq : node.requires()) {
            for (String member : prereq.members()) {
                NodeDef other = all.get(member);
                if (other != null && other.tier() != null && other.tier() >= node.tier()) {
                    return FailCodes.TIER_ORDER;
                }
                if (member.equals(node.id())) {
                    return FailCodes.TIER_ORDER;
                }
            }
        }
        String ns = node.namespace();
        for (Prereq prereq : node.requires()) {
            if (prereq instanceof Prereq.All required) {
                String other = NodeIds.namespace(required.node());
                if (!other.equals(ns) && !deps.required(ns, other)) {
                    return FailCodes.CROSS_WITHOUT_ALT;
                }
            }
        }
        if (node.badReference()) {
            return FailCodes.BAD_UNLOCK;
        }
        return null;
    }

    private static boolean loaded(String id, Map<String, NodeDef> all, Map<String, String> rejected) {
        return all.containsKey(id) && !rejected.containsKey(id);
    }

    private static boolean cascade(Map<String, NodeDef> all, Map<String, String> rejected) {
        boolean any = false;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NodeDef node : all.values()) {
                if (rejected.containsKey(node.id())) {
                    continue;
                }
                for (Prereq prereq : node.requires()) {
                    boolean ok = prereq.members().stream().anyMatch(m -> loaded(m, all, rejected));
                    if (prereq instanceof Prereq.All all1) {
                        ok = loaded(all1.node(), all, rejected);
                    }
                    if (!ok) {
                        rejected.put(node.id(), FailCodes.MISSING_PREREQ);
                        changed = true;
                        any = true;
                        break;
                    }
                }
            }
        }
        return any;
    }

    /** Tarjan SCC over accepted nodes; every node on a cycle is rejected. */
    private static boolean rejectCycles(Map<String, NodeDef> all, Map<String, String> rejected) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        int[] counter = {0};
        List<List<String>> cycles = new ArrayList<>();
        for (NodeDef node : all.values()) {
            if (!rejected.containsKey(node.id()) && !index.containsKey(node.id())) {
                strongConnect(node.id(), all, rejected, index, low, stack, onStack, counter, cycles);
            }
        }
        boolean changed = false;
        for (List<String> cycle : cycles) {
            for (String id : cycle) {
                if (rejected.putIfAbsent(id, FailCodes.CYCLE) == null) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void strongConnect(String start, Map<String, NodeDef> all, Map<String, String> rejected,
                                      Map<String, Integer> index, Map<String, Integer> low, Deque<String> stack,
                                      Set<String> onStack, int[] counter, List<List<String>> cycles) {
        // Iterative Tarjan to avoid deep recursion on long chains.
        Deque<Object[]> work = new ArrayDeque<>();
        work.push(new Object[]{start, 0});
        while (!work.isEmpty()) {
            Object[] frame = work.peek();
            String v = (String) frame[0];
            int edge = (int) frame[1];
            if (edge == 0 && !index.containsKey(v)) {
                index.put(v, counter[0]);
                low.put(v, counter[0]);
                counter[0]++;
                stack.push(v);
                onStack.add(v);
            }
            List<String> successors = successors(all.get(v), all, rejected);
            if (edge < successors.size()) {
                frame[1] = edge + 1;
                String w = successors.get(edge);
                if (!index.containsKey(w)) {
                    work.push(new Object[]{w, 0});
                } else if (onStack.contains(w)) {
                    low.put(v, Math.min(low.get(v), index.get(w)));
                }
                continue;
            }
            work.pop();
            if (!work.isEmpty()) {
                String parent = (String) work.peek()[0];
                low.put(parent, Math.min(low.get(parent), low.get(v)));
            }
            if (low.get(v).equals(index.get(v))) {
                List<String> component = new ArrayList<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    component.add(w);
                } while (!w.equals(v));
                boolean selfLoop = component.size() == 1 && successors(all.get(v), all, rejected).contains(v);
                if (component.size() > 1 || selfLoop) {
                    cycles.add(component);
                }
            }
        }
    }

    private static List<String> successors(NodeDef node, Map<String, NodeDef> all, Map<String, String> rejected) {
        List<String> out = new ArrayList<>();
        for (Prereq prereq : node.requires()) {
            for (String member : prereq.members()) {
                if (loaded(member, all, rejected)) {
                    out.add(member);
                }
            }
        }
        return out;
    }
}

