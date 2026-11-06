package com.xxsx.earthlore.mixin;

import com.xxsx.earthlore.gate.RecipeGates;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crafting table and the 2x2 inventory grid share this method (10 册 A-012 row 1). */
@Mixin(CraftingMenu.class)
abstract class CraftingMenuMixin {
    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void earthLore$gateResult(AbstractContainerMenu menu, ServerLevel level, Player player,
                                             CraftingContainer container, ResultContainer resultSlots,
                                             RecipeHolder<CraftingRecipe> recipeHint, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack result = resultSlots.getItem(0);
        if (result.isEmpty()) {
            return;
        }
        ItemStack gated = RecipeGates.forPlayer(serverPlayer, resultSlots.getRecipeUsed(), result);
        if (gated == result) {
            return;
        }
        resultSlots.setItem(0, gated);
        menu.setRemoteSlot(0, gated);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), 0, gated));
    }
}
