package com.xxsx.earthlore.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Value of a numeric research condition (field, aether, sanctum) at a research table, 0..100. */
@FunctionalInterface
public interface ConditionProvider {
    int value(ServerLevel level, BlockPos pos);
}

