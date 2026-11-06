package com.xxsx.earthlore.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Concatenating merge for list data maps; a repeated key keeps the first (earlier loaded) entry. */
public final class ListMerge {
    private ListMerge() {
    }

    public static <T, K> List<T> concatDistinct(List<T> first, List<T> second, Function<T, K> key) {
        List<T> out = new ArrayList<>(first.size() + second.size());
        Set<K> seen = new HashSet<>();
        for (T value : first) {
            if (seen.add(key.apply(value))) {
                out.add(value);
            }
        }
        for (T value : second) {
            if (seen.add(key.apply(value))) {
                out.add(value);
            }
        }
        return out;
    }
}

