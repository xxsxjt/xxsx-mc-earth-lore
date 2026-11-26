package com.xxsx.earthlore.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.network.LorePayloads;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.study.LiteratureItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;

/** Client wiring: payload handlers, screens, the lore tree key (default K), tint source, HUD and tooltips. */
public final class ClientLore {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(EarthLore.id("earth_lore"));
    public static final KeyMapping TREE_KEY = new KeyMapping("key.earth_lore.tree", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping INSPECT_KNOWLEDGE_KEY = new KeyMapping("key.earth_lore.inspect_knowledge",
            KeyConflictContext.GUI, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_W, CATEGORY);

    private ClientLore() {
    }

    public static void register(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterClientPayloadHandlersEvent.class, event -> {
            event.register(LorePayloads.SyncFull.TYPE, ClientKnowledge::onFull);
            event.register(LorePayloads.SyncDelta.TYPE, ClientKnowledge::onDelta);
            event.register(LorePayloads.StudyState.TYPE, ClientKnowledge::onStudyState);
        });
        modBus.addListener(RegisterMenuScreensEvent.class,
                event -> event.register(LoreContent.RESEARCH_TABLE_MENU.get(), ResearchTableScreen::new));
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.registerCategory(CATEGORY);
            event.register(TREE_KEY);
            event.register(INSPECT_KNOWLEDGE_KEY);
        });
        modBus.addListener(RegisterColorHandlersEvent.ItemTintSources.class,
                event -> event.register(EarthLore.id("discipline"), DisciplineTint.MAP_CODEC));
        modBus.addListener(RegisterGuiLayersEvent.class,
                event -> event.registerAbove(VanillaGuiLayers.HOTBAR, EarthLore.id("study"), StudyHud::render));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        NeoForge.EVENT_BUS.addListener(ItemTooltipEvent.class, ClientTooltips::onTooltip);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyPressed.Pre.class, ClientKnowledgeInspect::onKeyPressed);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyReleased.Pre.class, ClientKnowledgeInspect::onKeyReleased);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, ClientKnowledgeInspect::onScreenRender);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing.class, ClientKnowledgeInspect::onScreenClosing);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> ClientKnowledge.reset());
        LiteratureItem.clientCanStudy = ClientKnowledge::canStudy;
    }

    private static void tick() {
        ClientKnowledge.tick();
        Minecraft minecraft = Minecraft.getInstance();
        while (TREE_KEY.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                minecraft.setScreen(new LoreTreeScreen());
            }
        }
    }
}
