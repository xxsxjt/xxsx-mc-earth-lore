package com.xxsx.earthlore.gate;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * earth_lore:gated wrapper (10 册 A-012). Decoding returns the wrapped recipe itself, so it keeps its own
 * type and serializer and works in every station; the node is remembered by identity and mapped to the
 * recipe id after each data reload.
 */
public final class GatedRecipes {
    private static final Map<Recipe<?>, Identifier> PENDING = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Map<Identifier, Identifier> byRecipe = Map.of();

    public static final MapCodec<Recipe<?>> CODEC = RecordCodecBuilder.<Wrapped>mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("node").forGetter(Wrapped::node),
            Recipe.CODEC.fieldOf("recipe").forGetter(Wrapped::recipe)
    ).apply(instance, Wrapped::new)).xmap(wrapped -> {
        PENDING.put(wrapped.recipe(), wrapped.node());
        return wrapped.recipe();
    }, recipe -> new Wrapped(PENDING.getOrDefault(recipe, Identifier.withDefaultNamespace("missing")), recipe));

    private record Wrapped(Identifier node, Recipe<?> recipe) {
    }

    private GatedRecipes() {
    }

    /** Called after recipes (re)load: resolves remembered recipe objects to their ids. */
    public static void resolve(MinecraftServer server) {
        Map<Identifier, Identifier> next = new HashMap<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Identifier node = PENDING.get(holder.value());
            if (node != null) {
                next.put(holder.id().identifier(), node);
            }
        }
        byRecipe = Map.copyOf(next);
    }

    public static Optional<Identifier> nodeFor(Identifier recipe) {
        return Optional.ofNullable(byRecipe.get(recipe));
    }

    public static Map<Identifier, Identifier> all() {
        return byRecipe;
    }
}

