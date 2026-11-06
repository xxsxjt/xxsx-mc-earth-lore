package com.xxsx.earthlore.client;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.registry.LoreContent;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** earth_lore:discipline tint source (06 册 §1.1): the cover layer takes the main color of the text's discipline. */
public record DisciplineTint(int defaultColor) implements ItemTintSource {
    public static final MapCodec<DisciplineTint> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ExtraCodecs.RGB_COLOR_CODEC.optionalFieldOf("default", 0x808080).forGetter(DisciplineTint::defaultColor)
    ).apply(instance, DisciplineTint::new));

    @Override
    public int calculate(ItemStack stack, ClientLevel level, LivingEntity owner) {
        LoreText text = stack.get(LoreContent.LORE_TEXT.get());
        int rgb = text == null ? defaultColor : ClientKnowledge.color(text.discipline(), defaultColor);
        return ARGB.opaque(rgb);
    }

    @Override
    public MapCodec<DisciplineTint> type() {
        return MAP_CODEC;
    }
}
