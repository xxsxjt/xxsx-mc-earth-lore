package com.xxsx.earthlore.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Idempotent migration (10 册 A-015): migrators only add knowledge, run once per player unless forced,
 * and absent migrators are neither run nor recorded.
 */
public final class MigrationLedger {
    public record Outcome(Set<String> learned, Set<String> migrated, Set<String> added) {
    }

    private MigrationLedger() {
    }

    public static Outcome run(Set<String> learned, Set<String> migrated, List<String> installed,
                              Function<String, Set<String>> nodesFor, boolean force) {
        Set<String> outLearned = new LinkedHashSet<>(learned);
        Set<String> outMigrated = new LinkedHashSet<>(migrated);
        Set<String> added = new LinkedHashSet<>();
        for (String id : installed) {
            if (!force && outMigrated.contains(id)) {
                continue;
            }
            Set<String> nodes = nodesFor.apply(id);
            if (nodes != null) {
                for (String node : nodes) {
                    if (outLearned.add(node)) {
                        added.add(node);
                    }
                }
            }
            outMigrated.add(id);
        }
        return new Outcome(outLearned, outMigrated, added);
    }
}

