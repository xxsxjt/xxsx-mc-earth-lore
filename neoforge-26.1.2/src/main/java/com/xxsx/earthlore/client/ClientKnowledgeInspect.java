package com.xxsx.earthlore.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.registry.LoreContent;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Hold the configurable inspect key (default W, 0.5s) while hovering any knowledge-gated item
 * or literature in an inventory/container GUI or JEI overlay to jump straight to that node in
 * {@link LoreTreeScreen} with its prerequisite chain highlighted.
 */
public final class ClientKnowledgeInspect {
    public static final long HOLD_DURATION_MS = 500L;

    private static volatile Supplier<ItemStack> jeiHoverSupplier = () -> ItemStack.EMPTY;

    private static boolean keyHeld;
    private static long holdStartMs;
    private static Identifier trackedNodeId;

    private ClientKnowledgeInspect() {
    }

    public static void setJeiHoverSupplier(Supplier<ItemStack> supplier) {
        jeiHoverSupplier = supplier != null ? supplier : () -> ItemStack.EMPTY;
    }

    public static void clearJeiHoverSupplier() {
        jeiHoverSupplier = () -> ItemStack.EMPTY;
    }

    public static Optional<LoreGraph.Node> primaryNodeFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        LoreGraph graph = ClientKnowledge.graph();
        LoreGraph.Gates gates = ClientKnowledge.gates(stack);
        if (!gates.isEmpty()) {
            for (LoreGraph.Node node : gates.nodes()) {
                if (!ClientKnowledge.knows(node.id())) {
                    return Optional.of(node);
                }
            }
            return Optional.of(gates.nodes().get(0));
        }
        LoreText text = stack.get(LoreContent.LORE_TEXT.get());
        if (text != null) {
            LoreGraph.Node fallback = null;
            for (Identifier id : text.nodes()) {
                Optional<LoreGraph.Node> resolved = graph.node(id);
                if (resolved.isPresent()) {
                    if (!ClientKnowledge.knowsSelf(id)) {
                        return resolved;
                    }
                    if (fallback == null) {
                        fallback = resolved.get();
                    }
                }
            }
            if (fallback != null) {
                return Optional.of(fallback);
            }
        }
        return Optional.empty();
    }

    private static ItemStack hoveredStack(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = container.getHoveredSlot();
            if (slot != null && slot.hasItem()) {
                return slot.getItem();
            }
        }
        try {
            ItemStack jei = jeiHoverSupplier.get();
            if (jei != null && !jei.isEmpty()) {
                return jei;
            }
        } catch (Throwable ignored) {
            // Optional JEI overlay query
        }
        return ItemStack.EMPTY;
    }

    private static boolean isTypingInTextField(Screen screen) {
        return screen != null && screen.getFocused() instanceof EditBox box && box.canConsumeInput();
    }

    private static boolean isInspectKeyCurrentlyDown(Minecraft mc) {
        InputConstants.Key key = ClientLore.INSPECT_KNOWLEDGE_KEY.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
            return GLFW.glfwGetKey(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return keyHeld;
    }

    static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (screen instanceof LoreTreeScreen || isTypingInTextField(screen)) {
            return;
        }
        if (!ClientLore.INSPECT_KNOWLEDGE_KEY.matches(event.getKeyEvent())) {
            return;
        }
        ItemStack stack = hoveredStack(screen);
        Optional<LoreGraph.Node> target = primaryNodeFor(stack);
        if (target.isEmpty()) {
            return;
        }
        if (!keyHeld) {
            keyHeld = true;
            holdStartMs = System.currentTimeMillis();
            trackedNodeId = target.get().id();
        }
        event.setCanceled(true);
    }

    static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (ClientLore.INSPECT_KNOWLEDGE_KEY.matches(event.getKeyEvent())) {
            reset();
        }
    }

    static void onScreenClosing(ScreenEvent.Closing event) {
        reset();
    }

    static void onScreenRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || screen instanceof LoreTreeScreen || isTypingInTextField(screen)) {
            reset();
            return;
        }
        ItemStack stack = hoveredStack(screen);
        Optional<LoreGraph.Node> targetOpt = primaryNodeFor(stack);
        if (targetOpt.isEmpty()) {
            reset();
            return;
        }
        if (!isInspectKeyCurrentlyDown(mc)) {
            reset();
            return;
        }
        LoreGraph.Node target = targetOpt.get();
        long now = System.currentTimeMillis();
        if (!keyHeld || trackedNodeId == null || !trackedNodeId.equals(target.id())) {
            keyHeld = true;
            holdStartMs = now;
            trackedNodeId = target.id();
        }
        long elapsed = now - holdStartMs;
        if (elapsed >= HOLD_DURATION_MS) {
            reset();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.08F, 0.85F));
            mc.setScreen(new LoreTreeScreen(screen, target));
            return;
        }
        float progress = Mth.clamp((float) elapsed / (float) HOLD_DURATION_MS, 0.0F, 1.0F);
        drawProgressBar(event.getGuiGraphics(), mc.font, screen, event.getMouseX(), event.getMouseY(), target, progress);
    }

    private static void drawProgressBar(GuiGraphicsExtractor g, Font font, Screen screen, int mouseX, int mouseY,
                                        LoreGraph.Node target, float progress) {
        Component label = Component.translatable("tooltip.earth_lore.inspect.holding",
                ClientLore.INSPECT_KNOWLEDGE_KEY.getTranslatedKeyMessage(),
                Component.translatable(target.nameKey()));
        int w = Math.max(108, Math.min(200, font.width(label) + 12));
        int h = 20;
        int x = Mth.clamp(mouseX + 12, 6, Math.max(6, screen.width - w - 6));
        int y = mouseY - h - 10;
        if (y < 6) {
            y = Math.min(screen.height - h - 6, mouseY + 18);
        }
        g.fill(x, y, x + w, y + h, 0xE6101820);
        g.outline(x, y, w, h, 0xFFE5C158);
        g.text(font, LoreGui.fit(font, label, w - 8), x + 4, y + 3, 0xFFF5E6B8, true);
        int barX = x + 4;
        int barY = y + h - 5;
        int barW = w - 8;
        g.fill(barX, barY, barX + barW, barY + 3, 0xFF24303C);
        int fillW = Math.max(2, Math.round(barW * progress));
        g.fill(barX, barY, barX + fillW, barY + 3, 0xFF4BE0C8);
    }

    static void reset() {
        keyHeld = false;
        holdStartMs = 0L;
        trackedNodeId = null;
    }
}
