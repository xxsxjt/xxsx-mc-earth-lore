package com.xxsx.earthlore.registry;

import com.xxsx.earthlore.EarthLore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events of 06 册 §5.1. Each event is registered under its own id; sounds.json currently points them at
 * vanilla sounds, so dedicated recordings can replace them later without touching code.
 */
public final class LoreSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, EarthLore.MODID);

    /** Research table in the running state; replayed every RUNNING_INTERVAL ticks. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RESEARCH_RUNNING = register("block.research_table.running");
    public static final DeferredHolder<SoundEvent, SoundEvent> RESEARCH_DONE = register("block.research_table.done");
    /** Once every 20 ticks while studying. */
    public static final DeferredHolder<SoundEvent, SoundEvent> STUDY = register("item.lore_text.study");
    public static final DeferredHolder<SoundEvent, SoundEvent> LEARNED = register("item.lore_text.learned");
    /** Gate refusal or a failed research check. */
    public static final DeferredHolder<SoundEvent, SoundEvent> LOCKED = register("ui.lore.locked");

    public static final int RUNNING_INTERVAL = 60;
    private static final int LOCKED_INTERVAL = 20;
    private static final Map<UUID, Long> LAST_LOCKED = new ConcurrentHashMap<>();

    private LoreSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(EarthLore.id(path)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    /** Plays a sound for one player only, at the player's position. */
    public static void toPlayer(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }

    /** ui.lore.locked for one player, at most once a second, because crafting grids re-check on every slot change. */
    public static void locked(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long last = LAST_LOCKED.get(player.getUUID());
        if (last != null && now >= last && now - last < LOCKED_INTERVAL) {
            return;
        }
        LAST_LOCKED.put(player.getUUID(), now);
        toPlayer(player, LOCKED, 0.6F, 1.0F);
    }

    public static void forget(UUID player) {
        LAST_LOCKED.remove(player);
    }
}

