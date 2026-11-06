package com.xxsx.earthlore.knowledge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xxsx.earthlore.EarthLore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * earth_lore:knowledge_index (10 册 A-003 rule 6): a server-side mirror of every player's learned nodes,
 * so offline team members and SERVER scope count. The player attachment stays authoritative and
 * overwrites the mirror on login.
 */
public final class KnowledgeIndex extends SavedData {
    private record Entry(UUID id, String name, List<Identifier> nodes) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Entry::name),
                Identifier.CODEC.listOf().optionalFieldOf("nodes", List.of()).forGetter(Entry::nodes)
        ).apply(instance, Entry::new));
    }

    private static final Codec<KnowledgeIndex> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema", 1).forGetter(index -> 1),
            Entry.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(KnowledgeIndex::entries)
    ).apply(instance, (schema, entries) -> new KnowledgeIndex(entries)));

    public static final SavedDataType<KnowledgeIndex> TYPE = new SavedDataType<>(
            EarthLore.id("knowledge_index"), () -> new KnowledgeIndex(), CODEC);

    private final Map<UUID, Set<Identifier>> players = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public KnowledgeIndex() {
    }

    private KnowledgeIndex(List<Entry> entries) {
        for (Entry entry : entries) {
            players.put(entry.id(), new LinkedHashSet<>(entry.nodes()));
            if (!entry.name().isEmpty()) {
                names.put(entry.id(), entry.name());
            }
        }
    }

    public static KnowledgeIndex get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>(players.size());
        players.forEach((id, nodes) -> out.add(new Entry(id, names.getOrDefault(id, ""), List.copyOf(nodes))));
        return out;
    }

    public void put(UUID id, String name, Collection<Identifier> nodes) {
        Set<Identifier> next = new LinkedHashSet<>(nodes);
        boolean changed = !next.equals(players.get(id));
        players.put(id, next);
        if (name != null && !name.isEmpty() && !name.equals(names.get(id))) {
            names.put(id, name);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public Set<Identifier> nodes(UUID id) {
        return Collections.unmodifiableSet(players.getOrDefault(id, Set.of()));
    }

    public Set<UUID> players() {
        return Collections.unmodifiableSet(players.keySet());
    }

    public Optional<String> name(UUID id) {
        return Optional.ofNullable(names.get(id));
    }

    public Optional<UUID> byName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}

