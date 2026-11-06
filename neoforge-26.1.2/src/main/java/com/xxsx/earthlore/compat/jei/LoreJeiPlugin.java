package com.xxsx.earthlore.compat.jei;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.api.GateResult;
import com.xxsx.earthlore.client.ClientKnowledge;
import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.data.UnlockRef;
import com.xxsx.earthlore.graph.LoreGraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI view of knowledge gates (10 册 A-018): a lock on gated vanilla recipes with the gate nodes in its tooltip,
 * and an information page on every gated item. Recipes wrapped in earth_lore:gated keep their node on the
 * server only, so JEI marks them through their result item's gate when there is one.
 */
@JeiPlugin
public final class LoreJeiPlugin implements IModPlugin {
    private static final Identifier UID = EarthLore.id("jei");
    private static final int LOCK_W = 7;
    private static final int LOCK_H = 9;

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        decorate(registration, RecipeTypes.CRAFTING);
        decorate(registration, RecipeTypes.STONECUTTING);
        decorate(registration, RecipeTypes.SMELTING);
        decorate(registration, RecipeTypes.SMOKING);
        decorate(registration, RecipeTypes.BLASTING);
        decorate(registration, RecipeTypes.SMITHING);
    }

    private static <R extends Recipe<?>> void decorate(IAdvancedRegistration registration, IRecipeHolderType<R> type) {
        registration.addRecipeCategoryDecorator(type, new GateDecorator<>());
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        if (!LoreConfig.jeiShowLocked()) {
            return;
        }
        LoreGraph graph = ClientKnowledge.graph();
        Set<Item> done = new HashSet<>();
        for (LoreGraph.Node node : graph.nodes()) {
            for (UnlockRef unlock : node.data().unlocks()) {
                Optional<Item> item = gatedItem(unlock);
                if (item.isPresent() && item.get() != Items.AIR && done.add(item.get())) {
                    registration.addItemStackInfo(new ItemStack(item.get()), info(graph, node));
                }
            }
        }
    }

    private static Optional<Item> gatedItem(UnlockRef unlock) {
        Identifier id = Identifier.tryParse(unlock.value());
        if (id == null) {
            return Optional.empty();
        }
        return switch (unlock.kind()) {
            case ITEM -> BuiltInRegistries.ITEM.getOptional(id);
            case BLOCK -> BuiltInRegistries.BLOCK.getOptional(id).map(block -> block.asItem());
            default -> Optional.empty();
        };
    }

    private static Component[] info(LoreGraph graph, LoreGraph.Node node) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("jei.earth_lore.info.gate", Component.translatable(node.nameKey()),
                disciplineName(graph, node.discipline()), Component.translatable("tooltip.earth_lore.gate." + node.gate().id())));
        for (Prereq prereq : node.def().requires()) {
            MutableComponent names = Component.empty();
            List<String> members = prereq.members();
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    names.append(Component.translatable("jei.earth_lore.info.or"));
                }
                Identifier id = Identifier.tryParse(members.get(i));
                names.append(id == null ? Component.literal(members.get(i))
                        : graph.node(id).map(n -> (Component) Component.translatable(n.nameKey())).orElse(Component.literal(id.toString())));
            }
            lines.add(Component.translatable("jei.earth_lore.info.prereq", names));
        }
        lines.add(Component.translatable("jei.earth_lore.info.how"));
        return lines.toArray(Component[]::new);
    }

    private static Component disciplineName(LoreGraph graph, Identifier id) {
        return graph.discipline(id).map(d -> (Component) Component.translatable(d.nameKey())).orElse(Component.literal(id.toString()));
    }

    private static LoreGraph.Gates gates(RecipeHolder<?> recipe, IRecipeSlotsView slots) {
        List<Object> content = new ArrayList<>();
        content.add(recipe);
        for (IRecipeSlotView view : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
            view.getDisplayedItemStack().ifPresent(content::add);
        }
        return ClientKnowledge.gates(content);
    }

    private static final class GateDecorator<R extends Recipe<?>> implements IRecipeCategoryDecorator<RecipeHolder<R>> {
        @Override
        public void draw(RecipeHolder<R> recipe, IRecipeCategory<RecipeHolder<R>> category, IRecipeSlotsView slots,
                         GuiGraphicsExtractor g, double mouseX, double mouseY) {
            if (!LoreConfig.jeiShowLocked()) {
                return;
            }
            LoreGraph.Gates gates = gates(recipe, slots);
            if (gates.isEmpty()) {
                return;
            }
            GateResult result = ClientKnowledge.check(gates);
            int color = result.locked() ? 0xFFD04040 : result.status() == GateResult.Status.SOFT_CAPPED ? 0xFFE0A030 : 0xFF50B050;
            lock(g, category.getWidth() - LOCK_W, 0, color);
        }

        @Override
        public void decorateTooltips(ITooltipBuilder tooltip, RecipeHolder<R> recipe, IRecipeCategory<RecipeHolder<R>> category,
                                     IRecipeSlotsView slots, double mouseX, double mouseY) {
            if (!LoreConfig.jeiShowLocked() || mouseX < category.getWidth() - LOCK_W - 1 || mouseY < 0 || mouseY > LOCK_H + 1) {
                return;
            }
            LoreGraph.Gates gates = gates(recipe, slots);
            if (gates.isEmpty()) {
                return;
            }
            LoreGraph graph = ClientKnowledge.graph();
            for (LoreGraph.Node node : gates.nodes()) {
                boolean known = ClientKnowledge.knows(node.id());
                tooltip.add(Component.translatable("jei.earth_lore.needs", Component.translatable(node.nameKey()),
                        disciplineName(graph, node.discipline())).withStyle(known ? ChatFormatting.GREEN : ChatFormatting.GOLD));
            }
            if (gates.lockedByRejected()) {
                tooltip.add(Component.translatable("tooltip.earth_lore.gate.rejected").withStyle(ChatFormatting.RED));
            }
            GateResult result = ClientKnowledge.check(gates);
            if (result.locked()) {
                tooltip.add(Component.translatable("jei.earth_lore.locked").withStyle(ChatFormatting.RED));
            } else if (result.status() == GateResult.Status.SOFT_CAPPED) {
                tooltip.add(Component.translatable("jei.earth_lore.soft_cap",
                        Component.translatable("grade.earth_lore." + result.cap().id())).withStyle(ChatFormatting.YELLOW));
            } else {
                tooltip.add(Component.translatable("jei.earth_lore.unlocked").withStyle(ChatFormatting.GREEN));
            }
        }
    }

    private static void lock(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 1, y, x + 6, y + 1, color);
        g.fill(x + 1, y, x + 2, y + 4, color);
        g.fill(x + 5, y, x + 6, y + 4, color);
        g.fill(x, y + 4, x + 7, y + 9, color);
        g.fill(x + 3, y + 5, x + 4, y + 7, 0xFF2A1F16);
    }
}

