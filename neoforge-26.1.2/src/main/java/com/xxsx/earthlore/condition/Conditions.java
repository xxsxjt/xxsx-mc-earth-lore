package com.xxsx.earthlore.condition;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.api.ConditionProvider;
import com.xxsx.earthlore.api.PlayerConditionProvider;
import com.xxsx.earthlore.data.LoreCondition;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;

/** Evaluates research and study conditions (world conditions + player XP/realm conditions). */
public final class Conditions {
    private static final Map<String, ConditionProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<String, PlayerConditionProvider> PLAYER_PROVIDERS = new ConcurrentHashMap<>();

    private Conditions() {
    }

    /** Each key may be registered once; a second registration is logged and ignored. */
    public static void register(String key, ConditionProvider provider) {
        if (PROVIDERS.putIfAbsent(key, provider) != null) {
            EarthLore.LOGGER.error("[earth_lore] condition provider '{}' registered twice; keeping the first", key);
        }
    }

    /** Registers a numeric player condition provider (e.g. "realm") evaluated against the studying/researching player. */
    public static void registerPlayer(String key, PlayerConditionProvider provider) {
        if (PLAYER_PROVIDERS.putIfAbsent(key, provider) != null) {
            EarthLore.LOGGER.error("[earth_lore] player condition provider '{}' registered twice; keeping the first", key);
        }
    }

    public static boolean hasProvider(String key) {
        return PROVIDERS.containsKey(key) || PLAYER_PROVIDERS.containsKey(key);
    }

    public static void unregisterForTest(String key) {
        PROVIDERS.remove(key);
        PLAYER_PROVIDERS.remove(key);
    }

    public static boolean test(ServerLevel level, BlockPos pos, LoreCondition condition) {
        return test(level, pos, null, condition);
    }

    public static boolean test(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player, LoreCondition condition) {
        return switch (condition.kind()) {
            case NIGHT -> level.isDarkOutside();
            case DAY -> level.isBrightOutside();
            case SKY -> level.canSeeSky(pos.above());
            case RAIN -> level.isRaining();
            case THUNDER -> level.isThundering();
            case FIELD, AETHER, SANCTUM -> {
                ConditionProvider provider = PROVIDERS.get(condition.providerKey());
                yield provider != null && provider.value(level, pos) >= condition.number();
            }
            case XP_LEVEL -> player != null && (player.isCreative() || player.experienceLevel >= condition.number());
            case REALM -> {
                if (player == null) {
                    yield false;
                }
                if (player.isCreative()) {
                    yield true;
                }
                PlayerConditionProvider provider = PLAYER_PROVIDERS.get(condition.providerKey());
                yield provider != null && provider.value(player) >= condition.number();
            }
            case Y_AT_MOST -> pos.getY() <= condition.number();
            case Y_AT_LEAST -> pos.getY() >= condition.number();
            case DIM -> level.dimension().identifier().toString().equals(condition.id());
            case BIOME -> biome(level, pos, condition.id());
            case STRUCTURE -> structure(level, pos, condition.id());
            case INVALID -> false;
        };
    }

    private static boolean biome(ServerLevel level, BlockPos pos, String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            return false;
        }
        Holder<Biome> biome = level.getBiome(pos);
        return biome.is(TagKey.create(Registries.BIOME, parsed)) || biome.is(ResourceKey.create(Registries.BIOME, parsed));
    }

    private static boolean structure(ServerLevel level, BlockPos pos, String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            return false;
        }
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOptional(parsed)
                .map((Structure structure) -> level.structureManager().getStructureWithPieceAt(pos, structure).isValid())
                .orElse(false);
    }
}
