package com.xxsx.earthlore.knowledge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * earth_lore:knowledge player attachment (10 册 A-003). Unloaded node ids stay in the record so they
 * come back when their mod is reinstalled. Only KnowledgeService mutates it; subject mods go through
 * LoreApi.
 */
public final class PlayerKnowledge {
    public static final int SCHEMA = 1;
    public static final int MAX_LEARNED = 4096;

    public static final MapCodec<PlayerKnowledge> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema", SCHEMA).forGetter(k -> SCHEMA),
            Identifier.CODEC.listOf().optionalFieldOf("learned", List.of()).forGetter(k -> List.copyOf(k.learned)),
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG).optionalFieldOf("learned_at", Map.of())
                    .forGetter(k -> Map.copyOf(k.learnedAt)),
            Codec.STRING.listOf().optionalFieldOf("migrated", List.of()).forGetter(k -> List.copyOf(k.migrated))
    ).apply(instance, (schema, learned, learnedAt, migrated) -> new PlayerKnowledge(learned, learnedAt, migrated)));

    private final LinkedHashSet<Identifier> learned = new LinkedHashSet<>();
    private final Map<Identifier, Long> learnedAt = new HashMap<>();
    private final LinkedHashSet<String> migrated = new LinkedHashSet<>();

    public PlayerKnowledge() {
    }

    private PlayerKnowledge(List<Identifier> learned, Map<Identifier, Long> learnedAt, List<String> migrated) {
        this.learned.addAll(learned);
        this.learnedAt.putAll(learnedAt);
        this.migrated.addAll(migrated);
    }

    public Set<Identifier> learned() {
        return Collections.unmodifiableSet(learned);
    }

    public boolean has(Identifier node) {
        return learned.contains(node);
    }

    public long learnedAt(Identifier node) {
        return learnedAt.getOrDefault(node, 0L);
    }

    public Set<String> migrated() {
        return Collections.unmodifiableSet(migrated);
    }

    boolean add(Identifier node, long gameTime) {
        if (learned.contains(node) || learned.size() >= MAX_LEARNED) {
            return false;
        }
        learned.add(node);
        learnedAt.put(node, gameTime);
        return true;
    }

    boolean remove(Identifier node) {
        learnedAt.remove(node);
        return learned.remove(node);
    }

    void markMigrated(String migrator) {
        migrated.add(migrator);
    }
}

