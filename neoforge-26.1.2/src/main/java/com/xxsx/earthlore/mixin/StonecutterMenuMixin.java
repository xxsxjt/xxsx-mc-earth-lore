package com.xxsx.earthlore.mixin;

import com.xxsx.earthlore.gate.RecipeGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stonecutter, same rules as the crafting table (10 册 A-012 row 4). The menu keeps no player, so we remember it. */
@Mixin(StonecutterMenu.class)
abstract class StonecutterMenuMixin {
    @Shadow
    @Final
    private ResultContainer resultContainer;
    @Shadow
    @Final
    Slot resultSlot;
    @Unique
    private Player earthLore$player;

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
            at = @At("TAIL"))
    private void earthLore$rememberPlayer(int containerId, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
        this.earthLore$player = inventory.player;
    }

    @Inject(method = "setupResultSlot", at = @At("TAIL"))
    private void earthLore$gateResult(int index, CallbackInfo ci) {
        if (!(earthLore$player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack result = resultSlot.getItem();
        if (result.isEmpty()) {
            return;
        }
        ItemStack gated = RecipeGates.forPlayer(serverPlayer, resultContainer.getRecipeUsed(), result);
        if (gated != result) {
            resultSlot.set(gated);
            ((StonecutterMenu) (Object) this).broadcastChanges();
        }
    }
}
