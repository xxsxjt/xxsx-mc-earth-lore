package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.resources.Identifier;

/**
 * Research-time condition (10 册 A-002 condition grammar). Parsing never fails; an unknown string
 * becomes INVALID so the node is rejected with lore_bad_unlock instead of breaking the data pack.
 */
public record LoreCondition(Kind kind, int number, String id, String raw) {
    public enum Kind {
        NIGHT("needs_night"), DAY("needs_day"), SKY("sky_blocked"), RAIN("needs_rain"), THUNDER("needs_thunder"),
        FIELD("field_low"), AETHER("aether_low"), SANCTUM("sanctum_low"),
        Y_AT_MOST("too_shallow"), Y_AT_LEAST("too_low_altitude"),
        DIM("wrong_dimension"), BIOME("wrong_biome"), STRUCTURE("not_in_structure"), INVALID("lore_bad_unlock");

        private final String failCode;

        Kind(String failCode) {
            this.failCode = failCode;
        }

        public String failCode() {
            return failCode;
        }
    }

    public static final Codec<LoreCondition> CODEC = Codec.STRING.xmap(LoreCondition::parse, LoreCondition::raw);

    public static LoreCondition parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        switch (text) {
            case "night":
                return new LoreCondition(Kind.NIGHT, 0, "", text);
            case "day":
                return new LoreCondition(Kind.DAY, 0, "", text);
            case "sky":
                return new LoreCondition(Kind.SKY, 0, "", text);
            case "rain":
                return new LoreCondition(Kind.RAIN, 0, "", text);
            case "thunder":
                return new LoreCondition(Kind.THUNDER, 0, "", text);
            default:
                break;
        }
        for (Kind kind : new Kind[]{Kind.FIELD, Kind.AETHER, Kind.SANCTUM}) {
            String prefix = kind.name().toLowerCase(Locale.ROOT) + ">=";
            if (text.startsWith(prefix)) {
                Integer value = number(text.substring(prefix.length()));
                return value != null && value >= 0 && value <= 100
                        ? new LoreCondition(kind, value, "", text) : invalid(text);
            }
        }
        if (text.startsWith("y<=") || text.startsWith("y>=")) {
            Integer value = number(text.substring(3));
            return value == null ? invalid(text)
                    : new LoreCondition(text.startsWith("y<=") ? Kind.Y_AT_MOST : Kind.Y_AT_LEAST, value, "", text);
        }
        for (Kind kind : new Kind[]{Kind.DIM, Kind.BIOME, Kind.STRUCTURE}) {
            String prefix = kind.name().toLowerCase(Locale.ROOT) + ":";
            if (text.startsWith(prefix)) {
                String id = text.substring(prefix.length());
                if (id.startsWith("#")) {
                    id = id.substring(1);
                }
                return Identifier.tryParse(id) != null ? new LoreCondition(kind, 0, id, text) : invalid(text);
            }
        }
        return invalid(text);
    }

    private static LoreCondition invalid(String text) {
        return new LoreCondition(Kind.INVALID, 0, "", text);
    }

    private static Integer number(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String failCode() {
        return kind.failCode();
    }

    /** Key of the numeric condition provider (LoreApi.registerCondition), or empty for other kinds. */
    public String providerKey() {
        return switch (kind) {
            case FIELD -> "field";
            case AETHER -> "aether";
            case SANCTUM -> "sanctum";
            default -> "";
        };
    }
}

