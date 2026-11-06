package com.xxsx.earthlore.core;

import java.util.List;

/** A knowledge prerequisite: one required node, or a "satisfy any" group. */
public sealed interface Prereq permits Prereq.All, Prereq.Any {
    List<String> members();

    record All(String node) implements Prereq {
        @Override
        public List<String> members() {
            return List.of(node);
        }
    }

    record Any(List<String> nodes) implements Prereq {
        public Any {
            nodes = List.copyOf(nodes);
        }

        @Override
        public List<String> members() {
            return nodes;
        }
    }
}

