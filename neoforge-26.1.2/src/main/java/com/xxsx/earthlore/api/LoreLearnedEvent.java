package com.xxsx.earthlore.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/** Posted on NeoForge.EVENT_BUS when a player learns a node (10 册 A-011). */
public final class LoreLearnedEvent extends Event {
    public enum Source { STUDY, GRANT, MIGRATE }

    private final ServerPlayer player;
    private final Identifier node;
    private final Source source;
    private final ItemStack literature;

    public LoreLearnedEvent(ServerPlayer player, Identifier node, Source source, ItemStack literature) {
        this.player = player;
        this.node = node;
        this.source = source;
        this.literature = literature;
    }

    public ServerPlayer player() {
        return player;
    }

    public Identifier node() {
        return node;
    }

    public Source source() {
        return source;
    }

    /** Copy of the literature that was studied; empty for grant and migrate. */
    public ItemStack literature() {
        return literature;
    }
}

