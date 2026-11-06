package com.xxsx.earthlore.network;

import com.xxsx.earthlore.knowledge.LoreSync;
import com.xxsx.earthlore.research.ResearchTableBlockEntity;
import com.xxsx.earthlore.research.ResearchTableMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers payloads; client handlers are attached in the client-only event (ClientLore). */
public final class LoreNetwork {
    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private LoreNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToClient(LorePayloads.SyncFull.TYPE, LorePayloads.SyncFull.CODEC);
            registrar.playToClient(LorePayloads.SyncDelta.TYPE, LorePayloads.SyncDelta.CODEC);
            registrar.playToClient(LorePayloads.StudyState.TYPE, LorePayloads.StudyState.CODEC);
            registrar.playToServer(LorePayloads.ResearchStart.TYPE, LorePayloads.ResearchStart.CODEC, LoreNetwork::onStart);
            registrar.playToServer(LorePayloads.ResearchCancel.TYPE, LorePayloads.ResearchCancel.CODEC, LoreNetwork::onCancel);
            registrar.playToServer(LorePayloads.SyncRequest.TYPE, LorePayloads.SyncRequest.CODEC, LoreNetwork::onSyncRequest);
        });
    }

    /** The table must be within 8 blocks and its menu open (A-017). */
    private static ResearchTableBlockEntity table(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQ) {
            return null;
        }
        if (!(player.containerMenu instanceof ResearchTableMenu menu) || !menu.pos().equals(pos)) {
            return null;
        }
        BlockEntity be = player.level().getBlockEntity(pos);
        return be instanceof ResearchTableBlockEntity table ? table : null;
    }

    private static void onStart(LorePayloads.ResearchStart payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ResearchTableBlockEntity table = table(player, payload.pos());
            if (table != null) {
                table.start(player, payload.mode(), payload.target().orElse(null));
            }
        }
    }

    private static void onCancel(LorePayloads.ResearchCancel payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ResearchTableBlockEntity table = table(player, payload.pos());
            if (table != null) {
                table.cancel(player);
            }
        }
    }

    private static void onSyncRequest(LorePayloads.SyncRequest payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            LoreSync.sendFull(player);
        }
    }
}

