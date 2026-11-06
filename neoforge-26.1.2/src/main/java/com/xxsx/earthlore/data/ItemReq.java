package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Research-only requirement: hold an item, have made an item, or a location/time condition. */
public record ItemReq(Optional<LoreIngredient> hold, Optional<Identifier> made, Optional<LoreCondition> cond) {
    public static final Codec<ItemReq> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LoreIngredient.CODEC.optionalFieldOf("hold").forGetter(ItemReq::hold),
            Identifier.CODEC.optionalFieldOf("made").forGetter(ItemReq::made),
            LoreCondition.CODEC.optionalFieldOf("cond").forGetter(ItemReq::cond)
    ).apply(instance, ItemReq::new));

    public boolean isEmpty() {
        return hold.isEmpty() && made.isEmpty() && cond.isEmpty();
    }
}

