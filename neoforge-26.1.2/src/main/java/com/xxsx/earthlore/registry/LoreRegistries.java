package com.xxsx.earthlore.registry;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.KnowledgeNode;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/** Data-pack registries of 10 册 A-001 and A-002; both are synced to clients. */
public final class LoreRegistries {
    public static final ResourceKey<Registry<Discipline>> DISCIPLINE =
            ResourceKey.createRegistryKey(EarthLore.id("discipline"));
    /** Directory: data/<ns>/earth_lore/knowledge_node/<code>/<slug>.json */
    public static final ResourceKey<Registry<KnowledgeNode>> KNOWLEDGE_NODE =
            ResourceKey.createRegistryKey(EarthLore.id("knowledge_node"));

    private LoreRegistries() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(DataPackRegistryEvent.NewRegistry.class, event -> {
            event.dataPackRegistry(DISCIPLINE, Discipline.CODEC, Discipline.CODEC);
            event.dataPackRegistry(KNOWLEDGE_NODE, KnowledgeNode.CODEC, KnowledgeNode.CODEC);
        });
    }
}

