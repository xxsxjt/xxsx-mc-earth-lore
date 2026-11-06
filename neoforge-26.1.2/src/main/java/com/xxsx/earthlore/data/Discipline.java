package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import net.minecraft.resources.Identifier;

/**
 * One of the 14 disciplines (10 册 A-001). Shipped by earth_lore only; a malformed record is a
 * data-pack error and stops the world from loading, because every node hangs off this table.
 */
public record Discipline(String code, String nameKeyRaw, int order, int color, Identifier icon,
                         String homeMod, String baseMod, boolean hasRoot) {
    public static final Codec<Integer> COLOR_CODEC = Codec.withAlternative(Codec.INT,
            Codec.STRING.comapFlatMap(Discipline::parseColor, color -> String.format(Locale.ROOT, "#%06X", color)));

    public static final Codec<Discipline> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("code").forGetter(Discipline::code),
            Codec.STRING.optionalFieldOf("name_key", "").forGetter(Discipline::nameKeyRaw),
            Codec.intRange(0, 99).optionalFieldOf("order", 0).forGetter(Discipline::order),
            COLOR_CODEC.optionalFieldOf("color", 0x808080).forGetter(Discipline::color),
            Identifier.CODEC.optionalFieldOf("icon", Identifier.withDefaultNamespace("book")).forGetter(Discipline::icon),
            Codec.STRING.fieldOf("home_mod").forGetter(Discipline::homeMod),
            Codec.STRING.optionalFieldOf("base_mod", "").forGetter(Discipline::baseMod),
            Codec.BOOL.optionalFieldOf("has_root", true).forGetter(Discipline::hasRoot)
    ).apply(instance, Discipline::new));

    public String nameKey() {
        return nameKeyRaw.isEmpty() ? "discipline.earth_lore." + code.toLowerCase(Locale.ROOT) : nameKeyRaw;
    }

    public boolean validCode() {
        return code.length() == 3 && code.chars().allMatch(c -> c >= 'A' && c <= 'Z');
    }

    private static DataResult<Integer> parseColor(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw.startsWith("0x") ? raw.substring(2) : raw;
        try {
            int value = Integer.parseInt(hex, 16);
            return value >= 0 && value <= 0xFFFFFF ? DataResult.success(value)
                    : DataResult.error(() -> "color out of range: " + raw);
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "not a color: " + raw);
        }
    }
}

