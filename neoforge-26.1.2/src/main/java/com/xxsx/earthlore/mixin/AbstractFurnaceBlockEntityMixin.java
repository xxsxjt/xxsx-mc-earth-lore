package com.xxsx.earthlore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xxsx.earthlore.gate.RecipeGates;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Furnace, blast furnace and smoker (10 册 A-012 row 2). Both canBurn and burn see the same gated result, so a
 * lowered grade still stacks with earlier output and a hard lock keeps the furnace from starting.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class AbstractFurnaceBlockEntityMixin {
    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;canBurn(Lnet/minecraft/core/NonNullList;ILnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean earthLore$gateCanBurn(NonNullList<ItemStack> items, int maxStackSize, ItemStack result,
                                                 Operation<Boolean> original,
                                                 @Local(argsOnly = true) AbstractFurnaceBlockEntity entity,
                                                 @Local RecipeHolder<? extends AbstractCookingRecipe> recipe) {
        ItemStack gated = RecipeGates.forMachine(entity, recipe, result);
        if (gated.isEmpty()) {
            return false;
        }
        return original.call(items, maxStackSize, gated);
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;burn(Lnet/minecraft/core/NonNullList;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void earthLore$gateBurn(NonNullList<ItemStack> items, ItemStack ingredient, ItemStack result,
                                           Operation<Void> original,
                                           @Local(argsOnly = true) AbstractFurnaceBlockEntity entity,
                                           @Local RecipeHolder<? extends AbstractCookingRecipe> recipe) {
        ItemStack gated = RecipeGates.forMachine(entity, recipe, result);
        original.call(items, ingredient, gated.isEmpty() ? result : gated);
    }
}
