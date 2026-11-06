package com.xxsx.earthlore;

import com.xxsx.earthlore.client.ClientLore;
import com.xxsx.earthlore.gametest.LoreGameTests;
import com.xxsx.earthlore.network.LoreNetwork;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.registry.LoreRegistries;
import com.xxsx.earthlore.registry.LoreSounds;
import com.xxsx.earthlore.teach.TeachingService;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(EarthLore.MODID)
public final class EarthLore {
    public static final String MODID = "earth_lore";
    public static final Logger LOGGER = LoggerFactory.getLogger("earth_lore");

    public EarthLore(IEventBus modBus, ModContainer container) {
        LoreRegistries.register(modBus);
        LoreContent.register(modBus);
        LoreSounds.register(modBus);
        LoreNetwork.register(modBus);
        TeachingService.register(modBus);
        LoreGameTests.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, LoreConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, LoreConfig.CLIENT_SPEC);
        LoreEvents.register();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientLore.register(modBus, container);
        }
        LOGGER.info("[earth_lore] loaded");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
