package com.xxsx.earthlore.registry;

import com.xxsx.earthlore.EarthLore;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/** Item tags of 10 册 A-006 and A-009. */
public final class LoreTags {
    /** Blank carriers accepted by the research table; each also needs a carrier_forms.json declaration. */
    public static final TagKey<Item> CARRIERS = TagKey.create(Registries.ITEM, EarthLore.id("carriers"));
    /** Literature forms that start studying from their own use() through LoreApi.beginStudy. */
    public static final TagKey<Item> CUSTOM_STUDY = TagKey.create(Registries.ITEM, EarthLore.id("custom_study"));

    private LoreTags() {
    }
}
