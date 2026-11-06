package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import java.util.Locale;
import java.util.Map;

/** One gated content reference of a node (10 册 A-002 UnlockRef). */
public record UnlockRef(Kind kind, String value) {
    public enum Kind {
        ITEM, BLOCK, RECIPE, RECIPE_TYPE, ABILITY, READOUT, YIELD, CAPSTONE, INVALID;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byId(String id) {
            for (Kind kind : values()) {
                if (kind != INVALID && kind.id().equals(id)) {
                    return kind;
                }
            }
            return INVALID;
        }
    }

    public static final Codec<UnlockRef> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            .xmap(UnlockRef::fromMap, UnlockRef::toMap);

    private static UnlockRef fromMap(Map<String, String> map) {
        if (map.size() != 1) {
            return new UnlockRef(Kind.INVALID, map.toString());
        }
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        return new UnlockRef(Kind.byId(entry.getKey()), entry.getValue());
    }

    private Map<String, String> toMap() {
        return Map.of(kind == Kind.INVALID ? "invalid" : kind.id(), value);
    }

    /**
     * Normalized key for duplicate detection. Capstones are per discipline and owning mod (medicine has
     * one in earth_on_minecraft and one in earth_life_sciences).
     */
    public String key(String ownerNamespace) {
        return kind == Kind.CAPSTONE ? "capstone:" + value + "@" + ownerNamespace : kind.id() + ":" + value;
    }
}

