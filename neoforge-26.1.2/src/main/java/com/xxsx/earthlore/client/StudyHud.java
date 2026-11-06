package com.xxsx.earthlore.client;

import com.xxsx.earthlore.graph.LoreGraph;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Study progress bar above the hotbar (10 册 A-009 "客户端显示进度条"). */
final class StudyHud {
    private static final int WIDTH = 120;

    private StudyHud() {
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        Identifier node = ClientKnowledge.studyNode();
        if (node == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        LoreGraph graph = ClientKnowledge.graph();
        int total = Math.max(1, ClientKnowledge.studyTotal());
        int ticks = Math.min(total, ClientKnowledge.studyTicks());
        int x = (g.guiWidth() - WIDTH) / 2;
        int y = g.guiHeight() - 92;
        Component name = graph.node(node).map(n -> (Component) Component.translatable(n.nameKey()))
                .orElse(Component.literal(node.toString()));
        g.centeredText(minecraft.font, Component.translatable("hud.earth_lore.studying", name), g.guiWidth() / 2, y - 11, 0xFFFFFFFF);
        int color = graph.node(node).map(n -> ClientKnowledge.color(n.discipline(), 0xE8DCC0)).orElse(0xE8DCC0);
        g.fill(x - 1, y - 1, x + WIDTH + 1, y + 5, 0xC0000000);
        g.fill(x, y, x + WIDTH * ticks / total, y + 4, LoreColors.argb(LoreColors.highlight(color)));
    }
}
