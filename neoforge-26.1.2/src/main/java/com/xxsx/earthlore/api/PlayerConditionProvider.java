package com.xxsx.earthlore.api;

import net.minecraft.server.level.ServerPlayer;

/** Numeric player-state condition provider (e.g. cultivation realm, stamina tier) for knowledge unlock gates. */
@FunctionalInterface
public interface PlayerConditionProvider {
    int value(ServerPlayer player);
}
