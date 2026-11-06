package com.xxsx.earthlore.mixin;

import com.xxsx.earthlore.gate.RecipeGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smithing table, same rules as the crafting table (10 册 A-012 row 4). */
@Mixin(SmithingMenu.class)
abstract class SmithingMenuMixin extends ItemCombinerMenu {
    private SmithingMenuMixin(MenuType<?> type, int containerId, Inventory inventory, ContainerLevelAccess access,
                              ItemCombinerMenuSlotDefinition slots) {
        super(type, containerId, inventory, access, slots);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void earthLore$gateResult(CallbackInfo ci) {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack result = this.resultSlots.getItem(0);
        if (result.isEmpty()) {
            return;
        }
        ItemStack gated = RecipeGates.forPlayer(serverPlayer, this.resultSlots.getRecipeUsed(), result);
        if (gated != result) {
            this.resultSlots.setItem(0, gated);
        }
    }
}
