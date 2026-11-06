package com.xxsx.earthlore.api;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Lets earth_lore read and lower the quality grade that a subject mod stores on its items, so soft gates
 * at the crafting table and vanilla furnaces can cap grades without depending on that mod.
 */
public interface GradeAccess {
    Optional<Grade> read(ItemStack stack);

    /** Returns the stack with the given grade written; may be the same instance. */
    ItemStack write(ItemStack stack, Grade grade);
}

