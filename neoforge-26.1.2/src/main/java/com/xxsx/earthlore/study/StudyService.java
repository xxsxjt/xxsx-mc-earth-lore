package com.xxsx.earthlore.study;

import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.api.LoreLearnedEvent;
import com.xxsx.earthlore.api.StudyResult;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.core.StudyMath;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.network.LorePayloads;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.registry.LoreSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Study state machine of 10 册 A-009. A held session lives while the player keeps using the literature (use
 * duration > 0); a polled session, for literature forms that cannot be held in use, lives while use is pressed
 * again within 8 ticks. Letting go, switching hands or taking damage clears the current node's progress but
 * keeps nodes already learned.
 */
public final class StudyService {
    private static final int POLL_GRACE_TICKS = 8;

    private static final class Session {
        final InteractionHand hand;
        final ItemStack literature;
        final double factor;
        final boolean polled;
        Identifier node;
        int ticks;
        int total;
        int lastPoke;

        Session(InteractionHand hand, ItemStack literature, double factor, boolean polled) {
            this.hand = hand;
            this.literature = literature;
            this.factor = factor;
            this.polled = polled;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private StudyService() {
    }

    public static boolean studying(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** Current node and progress of a player's session, for tests and the HUD. */
    public static Optional<Identifier> currentNode(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null ? Optional.empty() : Optional.ofNullable(session.node);
    }

    public static int currentTicks(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null ? 0 : session.ticks;
    }

    /** Picks the first node that is loaded, not yet learned by this player, and whose prerequisites are met. */
    public static StudyResult select(ServerPlayer player, LoreText text, double factor) {
        LoreGraph graph = KnowledgeService.graph();
        List<Identifier> missing = new ArrayList<>();
        boolean anyLoaded = false;
        boolean anyUnknownToPlayer = false;
        for (Identifier id : text.nodes()) {
            Optional<LoreGraph.Node> node = graph.node(id);
            if (node.isEmpty()) {
                continue;
            }
            anyLoaded = true;
            if (KnowledgeService.knowsSelf(player, id)) {
                continue;
            }
            anyUnknownToPlayer = true;
            List<Identifier> lacking = new ArrayList<>();
            if (KnowledgeService.prerequisitesMet(player.getUUID(), node.get(), lacking)) {
                return StudyResult.ok(id, StudyMath.studyTicks(node.get().tier(), LoreConfig.studySpeed(), factor));
            }
            for (Identifier id2 : lacking) {
                if (!missing.contains(id2)) {
                    missing.add(id2);
                }
            }
        }
        boolean single = text.nodes().size() == 1;
        if (!anyLoaded) {
            return StudyResult.fail(single ? FailCodes.UNKNOWN_NODE : FailCodes.TEXT_EMPTY, List.of());
        }
        if (!anyUnknownToPlayer) {
            return StudyResult.fail(single ? FailCodes.ALREADY_KNOWN : FailCodes.TEXT_EMPTY, List.of());
        }
        return StudyResult.fail(FailCodes.PREREQ_MISSING, missing);
    }

    /** Held session: the caller makes the player use the item. */
    public static StudyResult begin(ServerPlayer player, ItemStack literature, double factor, InteractionHand hand) {
        return start(player, literature, factor, hand, false);
    }

    /** Polled session: each further use within 8 ticks keeps it alive. */
    public static StudyResult beginPolled(ServerPlayer player, ItemStack literature, double factor, InteractionHand hand) {
        Session current = SESSIONS.get(player.getUUID());
        if (current != null && current.polled && current.hand == hand
                && ItemStack.isSameItemSameComponents(current.literature, literature)) {
            current.lastPoke = tickCount(player);
            return StudyResult.ok(current.node, current.total);
        }
        return start(player, literature, factor, hand, true);
    }

    private static StudyResult start(ServerPlayer player, ItemStack literature, double factor, InteractionHand hand,
                                     boolean polled) {
        LoreText text = literature.get(LoreContent.LORE_TEXT.get());
        if (text == null || text.nodes().isEmpty()) {
            return StudyResult.fail(FailCodes.TEXT_EMPTY, List.of());
        }
        StudyResult result = select(player, text, factor);
        if (!result.started()) {
            return result;
        }
        Session session = new Session(hand, literature.copy(), factor, polled);
        session.node = result.node().orElseThrow();
        session.total = result.ticks();
        session.lastPoke = tickCount(player);
        SESSIONS.put(player.getUUID(), session);
        PacketDistributor.sendToPlayer(player, new LorePayloads.StudyState(Optional.of(session.node), session.total));
        return result;
    }

    private static int tickCount(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0 : server.getTickCount();
    }

    /** One use tick of a held session (LivingEntityUseItemEvent.Tick, server side). */
    public static void tick(ServerPlayer player, ItemStack using) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.polled) {
            return;
        }
        if (!player.isUsingItem() || player.getUsedItemHand() != session.hand || !sameItem(player, session)) {
            cancel(player);
            return;
        }
        advance(player, session);
    }

    /** Advances polled sessions once per server tick. */
    public static void serverTick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (UUID id : List.copyOf(SESSIONS.keySet())) {
            Session session = SESSIONS.get(id);
            if (session == null || !session.polled) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                SESSIONS.remove(id);
                continue;
            }
            if (now - session.lastPoke > POLL_GRACE_TICKS || !sameItem(player, session)) {
                cancel(player);
                continue;
            }
            advance(player, session);
        }
    }

    private static boolean sameItem(ServerPlayer player, Session session) {
        return ItemStack.isSameItemSameComponents(player.getItemInHand(session.hand), session.literature);
    }

    private static void advance(ServerPlayer player, Session session) {
        session.ticks++;
        if (session.ticks % 20 == 0) {
            player.level().playSound(null, player.blockPosition(), LoreSounds.STUDY.value(), SoundSource.PLAYERS, 0.6F, 1.0F);
        }
        if (session.ticks < session.total) {
            return;
        }
        Identifier learned = session.node;
        if (KnowledgeService.learn(player, learned, LoreLearnedEvent.Source.STUDY, session.literature)) {
            LoreGraph.Node node = KnowledgeService.graph().node(learned).orElse(null);
            Component name = node == null ? Component.literal(learned.toString()) : Component.translatable(node.nameKey());
            player.sendSystemMessage(Component.translatable("message.earth_lore.learned", name));
            player.level().playSound(null, player.blockPosition(), LoreSounds.LEARNED.value(), SoundSource.PLAYERS, 0.4F, 1.4F);
        }
        LoreText text = session.literature.get(LoreContent.LORE_TEXT.get());
        StudyResult next = text == null || text.nodes().size() < 2 ? null : select(player, text, session.factor);
        if (next != null && next.started()) {
            session.node = next.node().orElseThrow();
            session.ticks = 0;
            session.total = next.ticks();
            PacketDistributor.sendToPlayer(player, new LorePayloads.StudyState(Optional.of(session.node), session.total));
            return;
        }
        SESSIONS.remove(player.getUUID());
        if (!session.polled) {
            player.stopUsingItem();
        }
        PacketDistributor.sendToPlayer(player, new LorePayloads.StudyState(Optional.empty(), 0));
    }

    public static void cancel(ServerPlayer player) {
        if (SESSIONS.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new LorePayloads.StudyState(Optional.empty(), 0));
        }
    }

    public static void forget(UUID id) {
        SESSIONS.remove(id);
    }

    public static void clear() {
        SESSIONS.clear();
    }

    public static Component failMessage(StudyResult result) {
        Component base = Component.translatable("lore.earth_lore.fail." + result.failCode());
        if (result.missing().isEmpty()) {
            return base;
        }
        LoreGraph graph = KnowledgeService.graph();
        MutableComponent out = base.copy().append(": ");
        for (int i = 0; i < result.missing().size(); i++) {
            Identifier id = result.missing().get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append(graph.node(id).map(n -> (Component) Component.translatable(n.nameKey()))
                    .orElse(Component.literal(id.toString())));
        }
        return out;
    }
}
