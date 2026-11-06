package com.xxsx.earthlore.core;

import java.util.List;

/**
 * Validation view of one knowledge node. Fields that failed to parse are null so the validator can
 * report the right failure code instead of the data pack failing to load.
 *
 * @param unlockKeys   normalized content keys this node gates, used for duplicate detection
 * @param badReference true when an unlock target, research material or held item does not exist
 */
public record NodeDef(String id, String discipline, Integer tier, Gate gate, List<Prereq> requires,
                      List<String> unlockKeys, boolean badReference) {
    public NodeDef {
        requires = List.copyOf(requires);
        unlockKeys = List.copyOf(unlockKeys);
    }

    public String namespace() {
        return NodeIds.namespace(id);
    }

    public NodeDef withBadReference() {
        return new NodeDef(id, discipline, tier, gate, requires, unlockKeys, true);
    }
}

