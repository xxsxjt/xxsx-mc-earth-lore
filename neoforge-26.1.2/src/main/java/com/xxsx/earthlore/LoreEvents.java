package com.xxsx.earthlore;

import com.xxsx.earthlore.api.LoreMachines;
import com.xxsx.earthlore.api.StudyResult;
import com.xxsx.earthlore.command.LoreCommands;
import com.xxsx.earthlore.data.CarrierFormRegistry;
import com.xxsx.earthlore.gate.GatedRecipes;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.knowledge.LoreSync;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.registry.LoreSounds;
import com.xxsx.earthlore.registry.LoreTags;
import com.xxsx.earthlore.study.LiteratureItem;
import com.xxsx.earthlore.study.StudyService;
import com.xxsx.earthlore.teach.TeachingService;
import com.xxsx.earthlore.team.TeamTracker;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus wiring: server lifecycle, players, studying, machine owners, teaching, commands and reloads. */
final class LoreEvents {
    private LoreEvents() {
    }

    static void register() {
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(ServerStartedEvent.class, event -> started(event.getServer()));
        bus.addListener(ServerStoppedEvent.class, event -> stopped());
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player && KnowledgeService.running()) {
                KnowledgeService.onLogin(player);
            }
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                LoreSync.forget(player.getUUID());
                StudyService.forget(player.getUUID());
                LoreSounds.forget(player.getUUID());
                KnowledgeService.invalidate();
            }
        });
        bus.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player && KnowledgeService.running()) {
                LoreSync.sendFull(player);
            }
        });
        bus.addListener(PlayerEvent.PlayerRespawnEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player && KnowledgeService.running()) {
                StudyService.forget(player.getUUID());
                KnowledgeService.invalidate();
                LoreSync.sendFull(player);
            }
        });
        bus.addListener(ServerTickEvent.Post.class, event -> tick(event.getServer()));
        bus.addListener(LivingEntityUseItemEvent.Tick.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player && StudyService.studying(player)) {
                StudyService.tick(player, event.getItem());
            }
        });
        bus.addListener(LivingDamageEvent.Post.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player && event.getInflictedDamage() > 0
                    && StudyService.studying(player)) {
                StudyService.cancel(player);
                player.stopUsingItem();
            }
        });
        bus.addListener(PlayerInteractEvent.RightClickItem.class, LoreEvents::rightClickItem);
        bus.addListener(PlayerInteractEvent.RightClickBlock.class, LoreEvents::rightClickBlock);
        bus.addListener(PlayerInteractEvent.EntityInteract.class, event -> {
            if (!event.getLevel().isClientSide() && KnowledgeService.running()
                    && event.getTarget() instanceof AbstractVillager villager) {
                TeachingService.lockUnloaded(villager.getOffers());
            }
        });
        bus.addListener(BlockEvent.EntityPlaceEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player && event.getPlacedBlock().is(LoreMachines.RECORDS_OWNER)) {
                BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
                if (be != null) {
                    LoreMachines.setOwner(be, player);
                }
            }
        });
        bus.addListener(RegisterCommandsEvent.class, event -> LoreCommands.register(event.getDispatcher()));
        bus.addListener(AddServerReloadListenersEvent.class,
                event -> event.addListener(CarrierFormRegistry.ID, new CarrierFormRegistry()));
        bus.addListener(OnDatapackSyncEvent.class, event -> {
            if (event.getPlayer() == null && KnowledgeService.running()) {
                MinecraftServer server = KnowledgeService.server();
                GatedRecipes.resolve(server);
                KnowledgeService.revalidate();
                LoreSync.fullAll(server);
            }
        });
    }

    private static void started(MinecraftServer server) {
        LoreSync.clear();
        GatedRecipes.resolve(server);
        KnowledgeService.start(server);
    }

    private static void stopped() {
        KnowledgeService.stop();
        LoreSync.clear();
        StudyService.clear();
    }

    private static void tick(MinecraftServer server) {
        if (!KnowledgeService.running()) {
            return;
        }
        StudyService.serverTick(server);
        if (TeamTracker.tick(server)) {
            LoreSync.fullAll(server);
        }
    }

    /** A-009: any item with lore_text studies on use unless it handles studying itself (custom_study). */
    private static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (stack.getItem() instanceof LiteratureItem || !stack.has(LoreContent.LORE_TEXT.get())
                || stack.is(LoreTags.CUSTOM_STUDY)) {
            return;
        }
        event.setCanceled(true);
        InteractionHand hand = event.getHand();
        boolean held = stack.getUseDuration(event.getEntity()) > 0;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            if (held) {
                event.getEntity().startUsingItem(hand);
            }
            event.setCancellationResult(InteractionResult.CONSUME);
            return;
        }
        double factor = CarrierFormRegistry.studyFactor(stack.getItem());
        StudyResult result = held ? StudyService.begin(player, stack, factor, hand)
                : StudyService.beginPolled(player, stack, factor, hand);
        if (!result.started()) {
            player.sendOverlayMessage(StudyService.failMessage(result));
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (held) {
            player.startUsingItem(hand);
        }
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    /** A-013 rule 4: sneaking with both hands empty claims a machine that records an owner. */
    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isShiftKeyDown()
                || !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()
                || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        if (be == null || !(be.getBlockState().is(LoreMachines.RECORDS_OWNER)
                || be.getExistingData(LoreContent.MACHINE.get()).isPresent())) {
            return;
        }
        Optional<String> fail = LoreMachines.claim(be, player);
        player.sendOverlayMessage(fail.map(code -> (Component) Component.translatable("lore.earth_lore.fail." + code))
                .orElse(Component.translatable("message.earth_lore.claimed")));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS_SERVER);
    }
}
