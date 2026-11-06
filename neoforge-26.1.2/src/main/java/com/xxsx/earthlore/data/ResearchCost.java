package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

/** Materials consumed when research starts, and the base duration in ticks. */
public record ResearchCost(List<Material> items, int ticks) {
    public record Material(LoreIngredient ingredient, int count) {
        public static final Codec<Material> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                LoreIngredient.CODEC.fieldOf("ingredient").forGetter(Material::ingredient),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(Material::count)
        ).apply(instance, Material::new));
    }

    public static final ResearchCost NONE = new ResearchCost(List.of(), 0);

    public static final Codec<ResearchCost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Material.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(ResearchCost::items),
            Codec.intRange(0, 24000).optionalFieldOf("ticks", 0).forGetter(ResearchCost::ticks)
    ).apply(instance, ResearchCost::new));
}

