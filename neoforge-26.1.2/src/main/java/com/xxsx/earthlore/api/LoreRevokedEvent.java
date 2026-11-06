package com.xxsx.earthlore.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted when /lore revoke removes a node. Subject mods do not roll back values they granted. */
public final class LoreRevokedEvent extends Event {
    private final ServerPlayer player;
    private final Identifier node;

    public LoreRevokedEvent(ServerPlayer player, Identifier node) {
        this.player = player;
        this.node = node;
    }

    public ServerPlayer player() {
        return player;
    }

    public Identifier node() {
        return node;
    }
}

