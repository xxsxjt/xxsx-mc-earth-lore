package com.xxsx.earthlore.research;

import com.xxsx.earthlore.registry.LoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Menu of the research table; slot indices 0..11 match the block entity. */
public class ResearchTableMenu extends AbstractContainerMenu {
    public static final int IMAGE_WIDTH = 248;
    public static final int IMAGE_HEIGHT = 216;
    public static final int CARRIER_X = 132;
    public static final int CARRIER_Y = 22;
    public static final int MATERIAL_X = 132;
    public static final int MATERIAL_Y = 46;
    public static final int INSTRUMENT_X = 132;
    public static final int INSTRUMENT_Y = 90;
    public static final int OUTPUT_X = 222;
    public static final int OUTPUT_Y = 55;
    public static final int INVENTORY_X = 44;
    public static final int INVENTORY_Y = 134;

    private static final int TABLE_END = ResearchTableBlockEntity.SLOT_COUNT;
    private static final int INVENTORY_END = TABLE_END + 27;
    private static final int HOTBAR_END = INVENTORY_END + 9;

    private final Container container;
    private final ContainerData data;
    private final BlockPos pos;

    public ResearchTableMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, new SimpleContainer(ResearchTableBlockEntity.SLOT_COUNT),
                new SimpleContainerData(ResearchTableBlockEntity.DATA_COUNT), buf.readBlockPos());
    }

    public ResearchTableMenu(int containerId, Inventory inventory, ResearchTableBlockEntity table, ContainerData data) {
        this(containerId, inventory, table, data, table.getBlockPos());
    }

    private ResearchTableMenu(int containerId, Inventory inventory, Container container, ContainerData data, BlockPos pos) {
        super(LoreContent.RESEARCH_TABLE_MENU.get(), containerId);
        checkContainerSize(container, ResearchTableBlockEntity.SLOT_COUNT);
        checkContainerDataCount(data, ResearchTableBlockEntity.DATA_COUNT);
        this.container = container;
        this.data = data;
        this.pos = pos;
        container.startOpen(inventory.player);
        addSlot(new Slot(container, ResearchTableBlockEntity.SLOT_CARRIER, CARRIER_X, CARRIER_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ResearchTableBlockEntity.isCarrierSlotItem(stack);
            }
        });
        for (int i = 0; i < ResearchTableBlockEntity.MATERIAL_SLOTS; i++) {
            addSlot(new Slot(container, ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + i,
                    MATERIAL_X + (i % 4) * 18, MATERIAL_Y + (i / 4) * 18));
        }
        for (int i = 0; i < ResearchTableBlockEntity.INSTRUMENT_SLOTS; i++) {
            addSlot(new Slot(container, ResearchTableBlockEntity.SLOT_INSTRUMENT_FIRST + i, INSTRUMENT_X + i * 18, INSTRUMENT_Y));
        }
        addSlot(new Slot(container, ResearchTableBlockEntity.SLOT_OUTPUT, OUTPUT_X, OUTPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        addDataSlots(data);
    }

    public BlockPos pos() {
        return pos;
    }

    public int mode() {
        return data.get(ResearchTableBlockEntity.DATA_MODE);
    }

    public int progress() {
        return data.get(ResearchTableBlockEntity.DATA_PROGRESS);
    }

    public int total() {
        return data.get(ResearchTableBlockEntity.DATA_TOTAL);
    }

    public ResearchTableBlockEntity.State state() {
        return ResearchTableBlockEntity.State.byOrdinal(data.get(ResearchTableBlockEntity.DATA_STATE));
    }

    public String pauseReason() {
        return message(data.get(ResearchTableBlockEntity.DATA_PAUSE));
    }

    public String lastFail() {
        return message(data.get(ResearchTableBlockEntity.DATA_FAIL));
    }

    /** Index of the job's node in the synced node order, or -1. */
    public int targetIndex() {
        return (short) data.get(ResearchTableBlockEntity.DATA_TARGET);
    }

    private static String message(int index) {
        return index > 0 && index < ResearchTableBlockEntity.MESSAGES.size() ? ResearchTableBlockEntity.MESSAGES.get(index) : "";
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();
        if (index < TABLE_END) {
            if (!moveItemStackTo(stack, TABLE_END, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (ResearchTableBlockEntity.isCarrierSlotItem(stack)
                && moveItemStackTo(stack, ResearchTableBlockEntity.SLOT_CARRIER, ResearchTableBlockEntity.SLOT_CARRIER + 1, false)) {
            // moved into the carrier slot
        } else if (!moveItemStackTo(stack, ResearchTableBlockEntity.SLOT_MATERIAL_FIRST,
                ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + ResearchTableBlockEntity.MATERIAL_SLOTS, false)) {
            if (index < INVENTORY_END) {
                if (!moveItemStackTo(stack, INVENTORY_END, HOTBAR_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, TABLE_END, INVENTORY_END, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == moved.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        container.stopOpen(player);
    }
}
