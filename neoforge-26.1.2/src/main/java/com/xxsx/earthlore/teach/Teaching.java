package com.xxsx.earthlore.teach;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xxsx.earthlore.core.ListMerge;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.registries.datamaps.DataMapValueMerger;

/** One entry of the earth_lore:teaching data map (10 册 A-014). Price and level default from the node tier. */
public record Teaching(Identifier node, Optional<Integer> price, Optional<Integer> tradeLevel, int maxUses) {
    public static final Codec<Teaching> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("node").forGetter(Teaching::node),
            Codec.intRange(4, 24).optionalFieldOf("price").forGetter(Teaching::price),
            Codec.intRange(2, 3).optionalFieldOf("trade_level").forGetter(Teaching::tradeLevel),
            Codec.intRange(1, 12).optionalFieldOf("max_uses", 3).forGetter(Teaching::maxUses)
    ).apply(instance, Teaching::new));

    public static final Codec<List<Teaching>> LIST_CODEC = CODEC.listOf();

    /** Emeralds: 4 + 4 x tier unless set. */
    public int priceFor(int tier) {
        return price.orElse(Math.max(4, Math.min(24, 4 + 4 * tier)));
    }

    /** Villager level that offers the notes: tier + 1 unless set. */
    public int levelFor(int tier) {
        return tradeLevel.orElse(Math.max(2, Math.min(3, tier + 1)));
    }

    /** Lists from several mods are concatenated; a node listed twice keeps the earlier entry (A-014 rule 6). */
    public static <R> DataMapValueMerger<R, List<Teaching>> merger() {
        return (registry, first, firstValue, second, secondValue) ->
                ListMerge.concatDistinct(firstValue, secondValue, Teaching::node);
    }
}
