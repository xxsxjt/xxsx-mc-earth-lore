package com.xxsx.earthlore.mixin;

import com.xxsx.earthlore.teach.TeachingService;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds earth_lore:teaching offers when a villager gains the trades of a level (10 册 A-014). */
@Mixin(Villager.class)
abstract class VillagerMixin {
    @Inject(method = "updateTrades", at = @At("TAIL"))
    private void earthLore$addTeaching(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        VillagerData data = self.getVillagerData();
        List<MerchantOffer> offers = TeachingService.professionOffers(data.profession(), data.level());
        if (!offers.isEmpty()) {
            self.getOffers().addAll(offers);
        }
    }
}
