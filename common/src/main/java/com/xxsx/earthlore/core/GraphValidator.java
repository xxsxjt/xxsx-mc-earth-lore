package com.xxsx.earthlore.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Pure-Java graph validation (10 册 A-004, A-019) with automatic missing-companion-mod detection:
 * when a node references a prerequisite from an uninstalled optional companion mod, earth_lore
 * detects that the mod is not loaded and gracefully prunes that cross-mod prerequisite while still
 * strictly rejecting typos or broken references within installed mods.
 */
public final class GraphValidator {
    @FunctionalInterface
    public interface DependencyRule {
        boolean required(String ownerMod, String otherMod);
    }

    public record Result(Map<String, NodeDef> accepted, Map<String, String> rejected,
                         Set<String> missingCompanionMods, Map<String, List<String>> skippedUnloadedPrereqs) {
        public Result(Map<String, NodeDef> accepted, Map<String, String> rejected) {
            this(accepted, rejected, Set.of(), Map.of());
        }

        public Map<String, Integer> rejectedByCode() {
            Map<String, Integer> counts = new TreeMap<>();
            for (String code : rejected.values()) {
                counts.merge(code, 1, Integer::sum);
            }
            return Collections.unmodifiableMap(counts);
        }
    }

    private GraphValidator() {
    }

    /** Legacy 3-arg overload: assumes all referenced namespaces are installed mods. */
    public static Result validate(Collection<NodeDef> nodes, Set<String> disciplines, DependencyRule deps) {
        return validate(nodes, disciplines, deps, ns -> true);
    }

    /**
     * Full validator with {@code modLoaded} detection. Any prerequisite referencing an uninstalled
     * non-required companion mod is auto-detected, recorded in {@link Result#missingCompanionMods()},
     * and pruned from the active prerequisite list so optional cross-mod links never softlock nodes.
     */
    public static Result validate(Collection<NodeDef> nodes, Set<String> disciplines,
                                  DependencyRule deps, Predicate<String> modLoaded) {
        Map<String, NodeDef> raw = new LinkedHashMap<>();
        for (NodeDef node : nodes) {
            raw.put(node.id(), node);
        }

        Set<String> missingMods = new TreeSet<>();
        Map<String, List<String>> skippedPrereqs = new TreeMap<>();
        Map<String, NodeDef> all = new LinkedHashMap<>();
        for (NodeDef node : raw.values()) {
            all.put(node.id(), pruneUnloadedCompanionPrereqs(node, raw, deps, modLoaded, missingMods, skippedPrereqs));
        }

        Map<String, String> rejected = new TreeMap<>();
        for (NodeDef node : all.values()) {
            String failure = staticFailure(node, all, disciplines, deps);
            if (failure != null) {
                rejected.put(node.id(), failure);
            }
        }
        Map<String, List<String>> owners = new HashMap<>();
        for (NodeDef node : all.values()) {
            if (rejected.containsKey(node.id())) {
                continue;
            }
            for (String key : node.unlockKeys()) {
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
        Map<String, List<String>> immutableSkipped = new TreeMap<>();
        skippedPrereqs.forEach((k, v) -> immutableSkipped.put(k, List.copyOf(v)));
        return new Result(
                Collections.unmodifiableMap(accepted),
                Collections.unmodifiableMap(rejected),
                Collections.unmodifiableSet(missingMods),
                Collections.unmodifiableMap(immutableSkipped)
        );
    }

    private static NodeDef pruneUnloadedCompanionPrereqs(NodeDef node, Map<String, NodeDef> raw,
                                                         DependencyRule deps, Predicate<String> modLoaded,
                                                         Set<String> missingMods,
                                                         Map<String, List<String>> skippedPrereqs) {
        if (node.requires().isEmpty()) {
            return node;
        }
        String ns = node.namespace();
        List<Prereq> effective = new ArrayList<>(node.requires().size());
        boolean modified = false;
        for (Prereq prereq : node.requires()) {
            if (prereq instanceof Prereq.All required) {
                String otherNs = NodeIds.namespace(required.node());
                boolean unloadedCompanion = !raw.containsKey(required.node())
                        && !otherNs.equals(ns)
                        && !modLoaded.test(otherNs)
                        && !deps.required(ns, otherNs);
                if (unloadedCompanion) {
                    missingMods.add(otherNs);
                    skippedPrereqs.computeIfAbsent(node.id(), ignored -> new ArrayList<>()).add(required.node());
                    modified = true;
                    continue;
                }
                effective.add(prereq);
            } else if (prereq instanceof Prereq.Any group) {
                List<String> kept = new ArrayList<>();
                List<String> unloaded = new ArrayList<>();
                for (String member : group.nodes()) {
                    String otherNs = NodeIds.namespace(member);
                    boolean unloadedCompanion = !raw.containsKey(member)
                            && !otherNs.equals(ns)
                            && !modLoaded.test(otherNs)
                            && !deps.required(ns, otherNs);
                    if (unloadedCompanion) {
                        missingMods.add(otherNs);
                        unloaded.add(member);
                    } else {
                        kept.add(member);
                    }
                }
                if (!unloaded.isEmpty()) {
                    skippedPrereqs.computeIfAbsent(node.id(), ignored -> new ArrayList<>()).addAll(unloaded);
                    modified = true;
                }
                if (!kept.isEmpty()) {
                    effective.add(kept.size() == group.nodes().size() ? prereq : new Prereq.Any(List.copyOf(kept)));
                }
            }
        }
        if (!modified) {
            return node;
        }
        return new NodeDef(node.id(), node.discipline(), node.tier(), node.gate(),
                List.copyOf(effective), node.unlockKeys(), node.badReference());
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
                    if (prereq.members().stream().noneMatch(member -> loaded(member, all, rejected))) {
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
