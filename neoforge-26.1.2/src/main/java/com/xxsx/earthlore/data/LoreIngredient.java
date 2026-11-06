package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item matcher used by knowledge nodes. Accepts the 10 册 object form ({"item": id} / {"tag": id}) and
 * the vanilla string form ("ns:id" / "#ns:tag").
 */
public record LoreIngredient(Optional<Identifier> item, Optional<Identifier> tag) {
    private static final Codec<LoreIngredient> STRING_FORM = Codec.STRING.comapFlatMap(LoreIngredient::parse,
            LoreIngredient::toString);
    private static final Codec<LoreIngredient> OBJECT_FORM = RecordCodecBuilder.<LoreIngredient>create(instance -> instance.group(
            Identifier.CODEC.optionalFieldOf("item").forGetter(LoreIngredient::item),
            Identifier.CODEC.optionalFieldOf("tag").forGetter(LoreIngredient::tag)
    ).apply(instance, LoreIngredient::new)).validate(ingredient -> ingredient.item.isPresent() != ingredient.tag.isPresent()
            ? DataResult.success(ingredient) : DataResult.error(() -> "ingredient needs exactly one of item or tag"));
    public static final Codec<LoreIngredient> CODEC = Codec.withAlternative(STRING_FORM, OBJECT_FORM);

    public static LoreIngredient ofItem(Identifier id) {
        return new LoreIngredient(Optional.of(id), Optional.empty());
    }

    public static LoreIngredient ofTag(Identifier id) {
        return new LoreIngredient(Optional.empty(), Optional.of(id));
    }

    private static DataResult<LoreIngredient> parse(String raw) {
        if (raw.startsWith("#")) {
            return Identifier.read(raw.substring(1)).map(LoreIngredient::ofTag);
        }
        return Identifier.read(raw).map(LoreIngredient::ofItem);
    }

    public boolean test(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (item.isPresent()) {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item.get());
        }
        return tag.isPresent() && stack.is(TagKey.create(Registries.ITEM, tag.get()));
    }

    /** Tags cannot be checked before they load, so only plain item ids are verified. */
    public boolean exists() {
        return item.map(BuiltInRegistries.ITEM::containsKey).orElse(true);
    }

    public List<ItemStack> display() {
        List<ItemStack> out = new ArrayList<>();
        item.flatMap(BuiltInRegistries.ITEM::getOptional).ifPresent(i -> out.add(new ItemStack(i)));
        tag.ifPresent(t -> BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, t))
                .forEach(holder -> out.add(new ItemStack(holder))));
        return out;
    }

    public Optional<Item> asItem() {
        return item.flatMap(BuiltInRegistries.ITEM::getOptional);
    }

    @Override
    public String toString() {
        return item.map(Identifier::toString).orElseGet(() -> "#" + tag.map(Identifier::toString).orElse("minecraft:air"));
    }
}

