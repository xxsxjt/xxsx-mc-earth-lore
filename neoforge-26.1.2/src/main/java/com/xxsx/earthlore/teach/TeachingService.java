package com.xxsx.earthlore.teach;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.api.LoreLearnedEvent;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.graph.GraphBuilder;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.study.LiteratureItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.AdvancedDataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * Teaching (10 册 A-014): villagers and subject-mod merchants sell research notes for tier 1..2 nodes.
 * Villager offers are added when the villager reaches the entry's trade level (Villager.updateTrades mixin).
 */
public final class TeachingService {
    public static final DataMapType<VillagerProfession, List<Teaching>> PROFESSION =
            AdvancedDataMapType.builder(EarthLore.id("teaching"), Registries.VILLAGER_PROFESSION, Teaching.LIST_CODEC)
                    .merger(Teaching.<VillagerProfession>merger())
                    .build();
    public static final DataMapType<EntityType<?>, List<Teaching>> ENTITY =
            AdvancedDataMapType.builder(EarthLore.id("teaching"), Registries.ENTITY_TYPE, Teaching.LIST_CODEC)
                    .merger(Teaching.<EntityType<?>>merger())
                    .build();

    private static final int XP = 5;
    private static final float PRICE_MULTIPLIER = 0.05F;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private TeachingService() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterDataMapTypesEvent.class, event -> {
            event.register(PROFESSION);
            event.register(ENTITY);
        });
    }

    /** Offers a villager of this profession gains on reaching the given level. */
    public static List<MerchantOffer> professionOffers(Holder<VillagerProfession> profession, int level) {
        if (!LoreConfig.allowTeaching() || !KnowledgeService.running()) {
            return List.of();
        }
        List<Teaching> entries = profession.getData(PROFESSION);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        String owner = profession.unwrapKey().map(key -> key.identifier().getNamespace()).orElse("minecraft");
        return offersFor(entries, owner, level);
    }

    /** Offers for one villager level from a (merged) teaching list; also used by the teaching GameTest. */
    public static List<MerchantOffer> offersFor(List<Teaching> entries, String owner, int level) {
        List<MerchantOffer> out = new ArrayList<>();
        for (Teaching teaching : entries) {
            usable(teaching, owner).filter(node -> teaching.levelFor(node.tier()) == level)
                    .ifPresent(node -> out.add(offer(teaching, node)));
        }
        return out;
    }

    /** LoreApi.teachingOffers: every teachable entry of a merchant entity type, regardless of level. */
    public static List<MerchantOffer> entityOffers(EntityType<?> type) {
        if (!LoreConfig.allowTeaching() || !KnowledgeService.running()) {
            return List.of();
        }
        List<Teaching> entries = BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type).getData(ENTITY);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        String owner = BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace();
        List<MerchantOffer> out = new ArrayList<>();
        for (Teaching teaching : entries) {
            usable(teaching, owner).ifPresent(node -> out.add(offer(teaching, node)));
        }
        return out;
    }

    /** Rules 2–4: the node must be loaded, tier 1..2, and the teacher must belong to the node's mod or a dependency. */
    private static Optional<LoreGraph.Node> usable(Teaching teaching, String teacherNamespace) {
        Optional<LoreGraph.Node> node = KnowledgeService.graph().node(teaching.node());
        if (node.isEmpty()) {
            return Optional.empty();
        }
        int tier = node.get().tier();
        if (tier < 1 || tier > 2) {
            warn(teaching.node() + " tier " + tier + " cannot be taught (only tier 1..2)");
            return Optional.empty();
        }
        String nodeNamespace = node.get().namespace();
        if (!teacherNamespace.equals("minecraft") && !GraphBuilder.dependsOn(nodeNamespace, teacherNamespace)) {
            warn(teaching.node() + " cannot be taught by " + teacherNamespace + ": not the node's mod or a required dependency");
            return Optional.empty();
        }
        return node;
    }

    private static MerchantOffer offer(Teaching teaching, LoreGraph.Node node) {
        return new MerchantOffer(new ItemCost(Items.EMERALD, teaching.priceFor(node.tier())),
                LiteratureItem.notesFor(node.id(), node.discipline()), teaching.maxUses(), XP, PRICE_MULTIPLIER);
    }

    /** Rule 4: offers whose node is no longer loaded are locked (shown out of stock). */
    public static void lockUnloaded(MerchantOffers offers) {
        LoreGraph graph = KnowledgeService.graph();
        for (MerchantOffer offer : offers) {
            LoreText text = offer.getResult().get(LoreContent.LORE_TEXT.get());
            if (text != null && text.nodes().stream().noneMatch(graph::loaded) && !offer.isOutOfStock()) {
                offer.setToOutOfStock();
            }
        }
    }

    public static boolean hasTeachingOffers(MerchantOffers offers) {
        for (MerchantOffer offer : offers) {
            if (!offer.isOutOfStock() && offer.getResult().has(LoreContent.LORE_TEXT.get())) {
                return true;
            }
        }
        return false;
    }

    /** Plays librarian / page-turn audio and spawns knowledge glyph particles when a villager teaches or trades literature. */
    public static void playVillagerTeachingFeedback(ServerPlayer player, AbstractVillager villager) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        player.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_WORK_LIBRARIAN, SoundSource.NEUTRAL, 0.7F, 1.0F);
        serverLevel.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.7F, 1.05F);
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                villager.getX(), villager.getY() + 1.3, villager.getZ(),
                10, 0.4, 0.35, 0.4, 0.15);
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                player.getX(), player.getY() + 1.2, player.getZ(),
                8, 0.4, 0.35, 0.4, 0.15);
    }

    /**
     * Direct teaching / lecturing interaction when holding a written literature item and interacting with
     * another Player (or sneaking while interacting with a Villager/NPC). Triggers arm swing (InteractionResult.SUCCESS),
     * librarian/book page-turn sounds, and knowledge glyph particles.
     */
    public static InteractionResult interactTeach(Player teacher, LivingEntity target, ItemStack literature, InteractionHand hand) {
        LoreText text = literature.get(LoreContent.LORE_TEXT.get());
        if (text == null || text.nodes().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof Player) && !teacher.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (teacher.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(teacher instanceof ServerPlayer serverTeacher) || !(teacher.level() instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        serverTeacher.swing(hand, true);
        serverLevel.playSound(null, serverTeacher.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.75F, 1.0F);
        serverLevel.playSound(null, target.blockPosition(), SoundEvents.VILLAGER_WORK_LIBRARIAN, SoundSource.PLAYERS, 0.65F, 1.05F);
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                serverTeacher.getX(), serverTeacher.getY() + 1.25, serverTeacher.getZ(),
                8, 0.4, 0.35, 0.4, 0.15);
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                target.getX(), target.getY() + target.getBbHeight() * 0.7, target.getZ(),
                12, 0.45, 0.35, 0.45, 0.18);

        if (target instanceof ServerPlayer student && KnowledgeService.running() && LoreConfig.allowTeaching()) {
            LoreGraph graph = KnowledgeService.graph();
            for (Identifier id : text.nodes()) {
                Optional<LoreGraph.Node> nodeOpt = graph.node(id);
                if (nodeOpt.isEmpty() || !KnowledgeService.knows(serverTeacher.getUUID(), id)
                        || KnowledgeService.knowsSelf(student, id)) {
                    continue;
                }
                LoreGraph.Node node = nodeOpt.get();
                if (KnowledgeService.prerequisitesMet(student.getUUID(), node, null)
                        && KnowledgeService.learn(student, id, LoreLearnedEvent.Source.STUDY, literature)) {
                    Component name = Component.translatable(node.nameKey());
                    student.sendSystemMessage(Component.translatable("message.earth_lore.learned", name));
                    serverTeacher.sendOverlayMessage(Component.translatable("message.earth_lore.taught", student.getDisplayName(), name));
                    serverLevel.playSound(null, student.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 1.4F);
                    break;
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void warn(String message) {
        if (WARNED.add(message)) {
            EarthLore.LOGGER.warn("[earth_lore] teaching: {}", message);
        }
    }
}
