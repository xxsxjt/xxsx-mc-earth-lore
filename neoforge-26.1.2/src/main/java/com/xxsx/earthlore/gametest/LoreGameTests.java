package com.xxsx.earthlore.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.api.Grade;
import com.xxsx.earthlore.api.GradeAccess;
import com.xxsx.earthlore.api.LoreApi;
import com.xxsx.earthlore.api.LoreLearnedEvent;
import com.xxsx.earthlore.api.LoreMachines;
import com.xxsx.earthlore.api.LoreMigrator;
import com.xxsx.earthlore.api.StudyResult;
import com.xxsx.earthlore.condition.Conditions;
import com.xxsx.earthlore.core.CarrierForms;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.data.CarrierFormRegistry;
import com.xxsx.earthlore.data.LoreCondition;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.gate.MachineLore;
import com.xxsx.earthlore.gate.MachineLoreAccess;
import com.xxsx.earthlore.gate.RecipeGates;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeIndex;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.knowledge.Migrations;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.research.ResearchTableBlockEntity;
import com.xxsx.earthlore.study.LiteratureItem;
import com.xxsx.earthlore.study.StudyService;
import com.xxsx.earthlore.teach.Teaching;
import com.xxsx.earthlore.teach.TeachingService;
import com.xxsx.earthlore.team.TeamTracker;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * GameTests of 10 册 A-023 (names start with earth_lore_). Run them with gradlew runGameTestServer. Each test
 * makes its own mock players. Tests are grouped by environment: one batch per knowledge_scope, so a test that
 * needs team or server scope never runs next to one that assumes player scope.
 */
public final class LoreGameTests {
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");
    private static final DeferredRegister<MapCodec<? extends GameTestInstance>> TEST_TYPES =
            DeferredRegister.create(Registries.TEST_INSTANCE_TYPE, EarthLore.MODID);
    private static final DeferredRegister<MapCodec<? extends TestEnvironmentDefinition<?>>> ENVIRONMENT_TYPES =
            DeferredRegister.create(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, EarthLore.MODID);
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NAMES = new AtomicInteger();

    private static final BlockPos TABLE = new BlockPos(1, 1, 1);
    private static final BlockPos FURNACE = new BlockPos(3, 1, 1);
    private static final BlockPos FURNACE_2 = new BlockPos(3, 1, 3);
    private static final BlockPos FURNACE_3 = new BlockPos(1, 1, 3);

    private static final String GRADE_KEY = "earth_lore_test_grade";
    private static final AtomicBoolean GRADE_ACCESS = new AtomicBoolean();
    private static final AtomicBoolean MIGRATOR = new AtomicBoolean();
    private static final AtomicBoolean LISTENER = new AtomicBoolean();
    private static final Set<UUID> MIGRATE_FOR = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, List<LoreLearnedEvent>> EVENTS = new ConcurrentHashMap<>();

    static {
        TEST_TYPES.register("direct", () -> Direct.CODEC);
        ENVIRONMENT_TYPES.register("lore", () -> LoreEnvironment.CODEC);
    }

    private LoreGameTests() {
    }

    public static void register(IEventBus modBus) {
        TEST_TYPES.register(modBus);
        ENVIRONMENT_TYPES.register(modBus);
        modBus.addListener(LoreGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> playerScope =
                event.registerEnvironment(EarthLore.id("player_scope"), new LoreEnvironment("player", "auto"));
        Holder<TestEnvironmentDefinition<?>> teamScoreboard =
                event.registerEnvironment(EarthLore.id("team_scoreboard"), new LoreEnvironment("team", "scoreboard"));
        Holder<TestEnvironmentDefinition<?>> serverScope =
                event.registerEnvironment(EarthLore.id("server_scope"), new LoreEnvironment("server", "scoreboard"));
        test(event, "root_known", playerScope, 20, LoreGameTests::rootKnown);
        test(event, "study_ok", playerScope, 300, LoreGameTests::studyOk);
        test(event, "study_prereq_missing", playerScope, 20, LoreGameTests::studyPrereqMissing);
        test(event, "research_atomic", playerScope, 20, LoreGameTests::researchAtomic);
        test(event, "research_pause", playerScope, 400, LoreGameTests::researchPause);
        test(event, "cancel_refund", playerScope, 20, LoreGameTests::cancelRefund);
        test(event, "copy", playerScope, 400, LoreGameTests::copy);
        test(event, "treatise_order", playerScope, 400, LoreGameTests::treatiseOrder);
        test(event, "soft_cap", playerScope, 20, LoreGameTests::softCap);
        test(event, "cap_heirloom", playerScope, 20, LoreGameTests::capHeirloom);
        test(event, "hard_lock", playerScope, 200, LoreGameTests::hardLock);
        test(event, "owner_snapshot", playerScope, 20, LoreGameTests::ownerSnapshot);
        test(event, "any_alt", playerScope, 20, LoreGameTests::anyAlt);
        test(event, "migration_idempotent", playerScope, 20, LoreGameTests::migrationIdempotent);
        test(event, "death_keep", playerScope, 40, LoreGameTests::deathKeep);
        test(event, "unloaded_keep", playerScope, 20, LoreGameTests::unloadedKeep);
        test(event, "condition_provider", playerScope, 20, LoreGameTests::conditionProvider);
        test(event, "learned_event", playerScope, 300, LoreGameTests::learnedEvent);
        test(event, "carrier_filter", playerScope, 20, LoreGameTests::carrierFilter);
        test(event, "teaching_merge", playerScope, 20, LoreGameTests::teachingMerge);
        test(event, "team_scoreboard", teamScoreboard, 200, LoreGameTests::teamScoreboard);
        test(event, "scope_index", serverScope, 200, LoreGameTests::scopeIndex);
        if (ModList.get().isLoaded("ftbteams")) {
            Holder<TestEnvironmentDefinition<?>> teamFtb =
                    event.registerEnvironment(EarthLore.id("team_ftb"), new LoreEnvironment("team", "ftb_teams"));
            test(event, "team_ftb", teamFtb, 400, FtbTeamTest::run);
        }
    }

    private static void test(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> environment,
                             int maxTicks, Consumer<GameTestHelper> body) {
        String full = "earth_lore_" + name;
        TESTS.put(full, body);
        event.registerTest(EarthLore.id(full), new Direct(full,
                new TestData<>(environment, EMPTY_STRUCTURE, maxTicks, 1, true, Rotation.NONE, false, 1, 1, true, 4)));
    }

    // ---- mock players ----

    static ServerPlayer player(GameTestHelper helper) {
        return login(helper, new GameProfile(UUID.randomUUID(), name()));
    }

    static String name() {
        return "lore" + NAMES.incrementAndGet();
    }

    /** Like GameTestHelper.makeMockServerPlayerInLevel, but with a chosen profile so names and relogs are controlled. */
    static ServerPlayer login(GameTestHelper helper, GameProfile profile) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    static void logout(ServerPlayer player) {
        if (!player.isRemoved()) {
            player.level().getServer().getPlayerList().remove(player);
        }
    }

    private static void pokeStudy(ServerPlayer player) {
        if (!player.isRemoved() && StudyService.studying(player)) {
            StudyService.beginPolled(player, player.getMainHandItem(), 1.0, InteractionHand.MAIN_HAND);
        }
    }

    private static ItemStack notes(Identifier node) {
        return LiteratureItem.notesFor(node, LoreFixture.GEO);
    }

    private static ResearchTableBlockEntity table(GameTestHelper helper) {
        helper.setBlock(TABLE, LoreContent.RESEARCH_TABLE.get());
        return helper.getBlockEntity(TABLE, ResearchTableBlockEntity.class);
    }

    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.FURNACE);
        return helper.getBlockEntity(pos, AbstractFurnaceBlockEntity.class);
    }

    private static int count(ResearchTableBlockEntity table, Item item) {
        int total = 0;
        for (int s = 0; s < ResearchTableBlockEntity.MATERIAL_SLOTS; s++) {
            ItemStack stack = table.getItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + s);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ResearchTableBlockEntity startedResearch(GameTestHelper helper, ServerPlayer player) {
        ResearchTableBlockEntity table = table(helper);
        table.setItem(ResearchTableBlockEntity.SLOT_CARRIER, new ItemStack(LoreContent.BLANK_NOTES.get()));
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST, new ItemStack(Items.PAPER, 2));
        table.start(player, ResearchTableBlockEntity.MODE_RESEARCH, LoreFixture.BASIC);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.RUNNING, "research starts: " + table.lastFail());
        return table;
    }

    // ---- knowledge and study ----

    private static void rootKnown(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        helper.assertTrue(KnowledgeService.knows(player.getUUID(), LoreFixture.ROOT), "a new player knows the root node");
        helper.assertTrue(KnowledgeService.knowsSelf(player, LoreFixture.ROOT), "roots count as the player's own knowledge");
        helper.assertTrue(KnowledgeService.data(player).learned().isEmpty(), "a new player's learned set is empty");
        logout(player);
        helper.succeed();
    }

    private static void studyOk(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, notes(LoreFixture.BASIC));
        StudyResult result = StudyService.beginPolled(player, player.getMainHandItem(), 1.0, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.started(), "study starts: " + result.failCode());
        helper.onEachTick(() -> pokeStudy(player));
        helper.succeedWhen(() -> {
            helper.assertTrue(KnowledgeService.data(player).learned().contains(LoreFixture.BASIC), "studying adds the node");
            ItemStack held = player.getMainHandItem();
            helper.assertTrue(held.getCount() == 1 && held.has(LoreContent.LORE_TEXT.get()), "the notes are not consumed");
            logout(player);
        });
    }

    private static void studyPrereqMissing(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, notes(LoreFixture.ADVANCED));
        StudyResult result = StudyService.beginPolled(player, player.getMainHandItem(), 1.0, InteractionHand.MAIN_HAND);
        helper.assertFalse(result.started(), "study must not start without the prerequisite");
        helper.assertValueEqual(result.failCode(), FailCodes.PREREQ_MISSING, "fail code");
        helper.assertTrue(result.missing().contains(LoreFixture.BASIC), "the missing prerequisite is reported");
        helper.assertFalse(StudyService.studying(player), "no session is left behind");
        helper.assertTrue(KnowledgeService.data(player).learned().isEmpty(), "no progress is written");
        logout(player);
        helper.succeed();
    }

    // ---- research table ----

    private static void researchAtomic(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ResearchTableBlockEntity table = table(helper);
        table.setItem(ResearchTableBlockEntity.SLOT_CARRIER, new ItemStack(LoreContent.BLANK_NOTES.get()));
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST, new ItemStack(Items.PAPER));
        table.start(player, ResearchTableBlockEntity.MODE_RESEARCH, LoreFixture.BASIC);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.IDLE, "short materials keep the table idle");
        helper.assertValueEqual(table.lastFail(), FailCodes.COST_MISSING, "fail code");
        helper.assertValueEqual(count(table, Items.PAPER), 1, "no paper is taken");
        helper.assertValueEqual(table.getItem(ResearchTableBlockEntity.SLOT_CARRIER).getCount(), 1, "the carrier is not taken");
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + 3, new ItemStack(Items.PAPER));
        table.start(player, ResearchTableBlockEntity.MODE_RESEARCH, LoreFixture.BASIC);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.RUNNING, "research starts once materials suffice: " + table.lastFail());
        helper.assertValueEqual(count(table, Items.PAPER), 0, "both papers are taken in one step");
        helper.assertTrue(table.getItem(ResearchTableBlockEntity.SLOT_CARRIER).isEmpty(), "the carrier is taken");
        helper.assertValueEqual(table.consumedStacks(), 3, "carrier and two paper stacks are kept for a refund");
        logout(player);
        helper.succeed();
    }

    private static void researchPause(GameTestHelper helper) {
        helper.assertFalse(Conditions.hasProvider("aether"), "no subject mod may provide aether in the framework test server");
        AtomicInteger aether = new AtomicInteger(20);
        LoreApi.registerCondition("aether", (level, pos) -> aether.get());
        ServerPlayer player = player(helper);
        ResearchTableBlockEntity table = table(helper);
        table.setItem(ResearchTableBlockEntity.SLOT_CARRIER, new ItemStack(LoreContent.BLANK_NOTES.get()));
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST, new ItemStack(Items.PAPER));
        table.start(player, ResearchTableBlockEntity.MODE_RESEARCH, LoreFixture.AETHER);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.RUNNING, "research starts: " + table.lastFail());
        int[] mark = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(table.progress() >= 40, "progress advances"))
                .thenExecute(() -> aether.set(0))
                .thenWaitUntil(() -> helper.assertTrue(table.state() == ResearchTableBlockEntity.State.PAUSED, "a failed condition pauses"))
                .thenExecute(() -> {
                    helper.assertValueEqual(table.pauseReason(), LoreCondition.Kind.AETHER.failCode(), "pause reason");
                    mark[0] = table.progress();
                })
                .thenIdle(45)
                .thenExecute(() -> {
                    helper.assertTrue(table.state() == ResearchTableBlockEntity.State.PAUSED, "still paused");
                    helper.assertValueEqual(table.progress(), mark[0], "progress is kept while paused");
                    aether.set(20);
                })
                .thenWaitUntil(() -> helper.assertTrue(table.state() == ResearchTableBlockEntity.State.RUNNING
                        && table.progress() > mark[0], "research resumes from where it stopped"))
                .thenExecute(() -> {
                    Conditions.unregisterForTest("aether");
                    logout(player);
                })
                .thenSucceed();
    }

    private static void cancelRefund(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ResearchTableBlockEntity table = startedResearch(helper, player);
        table.cancel(player);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.IDLE, "cancel returns the table to idle");
        ItemStack carrier = table.getItem(ResearchTableBlockEntity.SLOT_CARRIER);
        helper.assertTrue(carrier.is(LoreContent.BLANK_NOTES.get()) && carrier.getCount() == 1, "the carrier is returned");
        helper.assertValueEqual(count(table, Items.PAPER), 2, "every paper is returned");
        helper.assertValueEqual(table.consumedStacks(), 0, "nothing is left in the refund list");
        logout(player);
        helper.succeed();
    }

    private static void copy(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ResearchTableBlockEntity table = table(helper);
        ItemStack original = notes(LoreFixture.BASIC);
        LoreText text = original.get(LoreContent.LORE_TEXT.get());
        helper.assertTrue(text != null, "the fixture notes carry lore_text");
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST, original.copy());
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + 1, new ItemStack(Items.INK_SAC));
        table.setItem(ResearchTableBlockEntity.SLOT_CARRIER, new ItemStack(LoreContent.BLANK_NOTES.get()));
        table.start(player, ResearchTableBlockEntity.MODE_COPY, null);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.RUNNING, "copying starts: " + table.lastFail());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(table.state() == ResearchTableBlockEntity.State.DONE, "copying finishes"))
                .thenExecute(() -> {
                    LoreText copied = table.getItem(ResearchTableBlockEntity.SLOT_OUTPUT).get(LoreContent.LORE_TEXT.get());
                    helper.assertTrue(copied != null, "the copy carries lore_text");
                    helper.assertValueEqual(copied.nodes(), text.nodes(), "the copy has the same nodes");
                    helper.assertValueEqual(copied.copyGeneration(), text.copyGeneration() + 1, "copy_generation goes up by one");
                    ItemStack kept = table.getItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST);
                    helper.assertTrue(kept.getCount() == 1 && ItemStack.isSameItemSameComponents(kept, original), "the original stays");
                    logout(player);
                })
                .thenSucceed();
    }

    private static void treatiseOrder(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ResearchTableBlockEntity table = table(helper);
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST, notes(LoreFixture.ADVANCED));
        table.setItem(ResearchTableBlockEntity.SLOT_MATERIAL_FIRST + 1, notes(LoreFixture.BASIC));
        table.setItem(ResearchTableBlockEntity.SLOT_CARRIER, new ItemStack(Items.BOOK));
        table.start(player, ResearchTableBlockEntity.MODE_COMPILE, null);
        helper.assertTrue(table.state() == ResearchTableBlockEntity.State.RUNNING, "compiling starts: " + table.lastFail());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(table.state() == ResearchTableBlockEntity.State.DONE, "compiling finishes"))
                .thenExecute(() -> {
                    LoreText text = table.getItem(ResearchTableBlockEntity.SLOT_OUTPUT).get(LoreContent.LORE_TEXT.get());
                    helper.assertTrue(text != null, "the treatise carries lore_text");
                    helper.assertValueEqual(text.nodes(), List.of(LoreFixture.BASIC, LoreFixture.ADVANCED), "nodes in study order");
                    StudyResult first = StudyService.select(player, text, 1.0);
                    helper.assertValueEqual(first.node(), Optional.of(LoreFixture.BASIC), "the prerequisite is studied first");
                    KnowledgeService.grantAll(player, List.of(LoreFixture.BASIC));
                    StudyResult second = StudyService.select(player, text, 1.0);
                    helper.assertValueEqual(second.node(), Optional.of(LoreFixture.ADVANCED), "then the dependent node");
                    logout(player);
                })
                .thenSucceed();
    }

    // ---- gates ----

    private static void ensureGradeAccess() {
        if (GRADE_ACCESS.compareAndSet(false, true)) {
            LoreApi.registerGradeAccess(new GradeAccess() {
                @Override
                public Optional<Grade> read(ItemStack stack) {
                    CustomData data = stack.get(DataComponents.CUSTOM_DATA);
                    if (data == null || !data.contains(GRADE_KEY)) {
                        return Optional.empty();
                    }
                    return data.copyTag().getString(GRADE_KEY).flatMap(Grade::byId);
                }

                @Override
                public ItemStack write(ItemStack stack, Grade grade) {
                    CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(GRADE_KEY, grade.id()));
                    return stack;
                }
            });
        }
    }

    private static ItemStack graded(Item item, Grade grade) {
        ItemStack stack = new ItemStack(item);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(GRADE_KEY, grade.id()));
        return stack;
    }

    private static Grade gradeOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? Grade.CRUDE : data.copyTag().getString(GRADE_KEY).flatMap(Grade::byId).orElse(Grade.CRUDE);
    }

    private static void softCap(GameTestHelper helper) {
        ensureGradeAccess();
        ServerPlayer player = player(helper);
        ItemStack before = RecipeGates.forPlayer(player, null, graded(Items.WOODEN_SHOVEL, Grade.HEIRLOOM));
        helper.assertFalse(before.isEmpty(), "a soft gate never empties the result");
        helper.assertTrue(gradeOf(before).tier() <= Grade.COMMON.tier(), "without the soft node the grade is at most common");
        KnowledgeService.grantAll(player, List.of(LoreFixture.SOFT));
        ItemStack after = RecipeGates.forPlayer(player, null, graded(Items.WOODEN_SHOVEL, Grade.HEIRLOOM));
        helper.assertTrue(gradeOf(after).tier() <= Grade.MASTERWORK.tier(), "with the soft node the grade is at most masterwork");
        helper.assertValueEqual(gradeOf(after), Grade.MASTERWORK, "the cap rises to masterwork");
        logout(player);
        helper.succeed();
    }

    private static void capHeirloom(GameTestHelper helper) {
        ensureGradeAccess();
        ServerPlayer player = player(helper);
        KnowledgeService.grantAll(player, List.of(LoreFixture.SOFT, LoreFixture.CAPSTONE));
        ItemStack result = RecipeGates.forPlayer(player, null, graded(Items.WOODEN_SHOVEL, Grade.HEIRLOOM));
        helper.assertValueEqual(gradeOf(result), Grade.HEIRLOOM, "the capstone allows heirloom");
        logout(player);
        helper.succeed();
    }

    private static void hardLock(GameTestHelper helper) {
        ServerPlayer crafter = player(helper);
        CraftingMenu menu = new CraftingMenu(0, crafter.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(TABLE)));
        menu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
        menu.getSlot(2).set(new ItemStack(Items.OAK_PLANKS));
        menu.getSlot(5).set(new ItemStack(Items.STICK));
        menu.getSlot(8).set(new ItemStack(Items.STICK));
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "the crafting result stays empty without the hard node");
        KnowledgeService.grantAll(crafter, List.of(LoreFixture.HARD));
        menu.getSlot(8).set(new ItemStack(Items.STICK));
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.WOODEN_HOE), "the recipe works once the node is learned");

        ServerPlayer owner = player(helper);
        AbstractFurnaceBlockEntity furnace = furnace(helper, FURNACE);
        LoreMachines.setOwner(furnace, owner);
        furnace.setItem(0, new ItemStack(Items.OAK_LOG));
        furnace.setItem(1, new ItemStack(Items.COAL));
        helper.succeedWhen(() -> {
            MachineLore lore = furnace.getExistingDataOrNull(LoreContent.MACHINE.get());
            helper.assertTrue(lore != null && FailCodes.LOCKED.equals(lore.fault()), "the furnace reports lore_locked");
            helper.assertTrue(furnace.getItem(2).isEmpty(), "the furnace produces nothing");
            helper.assertValueEqual(furnace.getItem(1).getCount(), 1, "no fuel is burnt");
            logout(crafter);
            logout(owner);
        });
    }

    private static void ownerSnapshot(GameTestHelper helper) {
        ItemStack charcoal = new ItemStack(Items.CHARCOAL);
        ServerPlayer owner = player(helper);
        KnowledgeService.grantAll(owner, List.of(LoreFixture.HARD));
        AbstractFurnaceBlockEntity first = furnace(helper, FURNACE);
        LoreMachines.setOwner(first, owner);
        helper.assertFalse(LoreMachines.check(first, charcoal).locked(), "an online owner who knows the node");
        helper.assertTrue(first.getData(LoreContent.MACHINE.get()).snapshot().contains(LoreFixture.HARD),
                "the check stores the node in the snapshot");
        logout(owner);
        helper.assertFalse(LoreMachines.check(first, charcoal).locked(), "an offline owner keeps the last known knowledge");

        AbstractFurnaceBlockEntity second = furnace(helper, FURNACE_2);
        MachineLore remembered = second.getData(LoreContent.MACHINE.get());
        MachineLoreAccess.setOwner(remembered, UUID.randomUUID());
        MachineLoreAccess.remember(remembered, List.of(LoreFixture.HARD), Set.of(LoreFixture.HARD), helper.getLevel().getGameTime());
        helper.assertFalse(LoreMachines.check(second, charcoal).locked(), "an owner the server has no record of is judged by the snapshot");

        AbstractFurnaceBlockEntity third = furnace(helper, FURNACE_3);
        MachineLoreAccess.setOwner(third.getData(LoreContent.MACHINE.get()), UUID.randomUUID());
        helper.assertTrue(LoreMachines.check(third, charcoal).locked(), "an empty snapshot refuses hard content");
        helper.succeed();
    }

    private static void anyAlt(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        LoreGraph graph = KnowledgeService.graph();
        Optional<LoreGraph.Node> node = graph.node(LoreFixture.ANY);
        helper.assertTrue(node.isPresent(), "the any node is loaded");
        helper.assertFalse(graph.loaded(LoreFixture.ABSENT), "the other member's mod is not installed");
        helper.assertFalse(KnowledgeService.prerequisitesMet(player.getUUID(), node.get(), null), "not learnable before the alternative");
        KnowledgeService.grantAll(player, List.of(LoreFixture.BASIC));
        helper.assertTrue(KnowledgeService.prerequisitesMet(player.getUUID(), node.get(), null),
                "the installed alternative satisfies the any group");
        logout(player);
        helper.succeed();
    }

    // ---- records ----

    private static void ensureMigrator() {
        if (MIGRATOR.compareAndSet(false, true)) {
            LoreApi.registerMigrator(new LoreMigrator() {
                @Override
                public String id() {
                    return "earth_lore:gametest";
                }

                @Override
                public Set<Identifier> nodesFor(ServerPlayer player) {
                    return MIGRATE_FOR.contains(player.getUUID()) ? Set.of(LoreFixture.MIGRATED) : Set.of();
                }
            });
        }
    }

    private static void migrationIdempotent(GameTestHelper helper) {
        ensureMigrator();
        ServerPlayer player = player(helper);
        MIGRATE_FOR.add(player.getUUID());
        List<Identifier> first = Migrations.rerun(player);
        Set<Identifier> once = Set.copyOf(KnowledgeService.data(player).learned());
        helper.assertTrue(first.contains(LoreFixture.MIGRATED) && once.contains(LoreFixture.MIGRATED), "the first run adds the node");
        List<Identifier> second = Migrations.rerun(player);
        helper.assertTrue(second.isEmpty(), "the second run adds nothing");
        helper.assertValueEqual(Set.copyOf(KnowledgeService.data(player).learned()), once, "learned is unchanged");
        MIGRATE_FOR.remove(player.getUUID());
        logout(player);
        helper.succeed();
    }

    private static void deathKeep(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        KnowledgeService.grantAll(player, List.of(LoreFixture.BASIC));
        ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        helper.assertTrue(KnowledgeService.data(respawned).learned().contains(LoreFixture.BASIC), "learned survives death");
        logout(respawned);
        helper.succeed();
    }

    private static void unloadedKeep(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        KnowledgeService.grantAll(player, List.of(LoreFixture.GHOST));
        helper.assertTrue(KnowledgeService.knows(player.getUUID(), LoreFixture.GHOST), "learned while the mod is installed");
        LoreFixture.install(false);
        try {
            helper.assertFalse(KnowledgeService.knows(player.getUUID(), LoreFixture.GHOST), "an unloaded node is not known");
            helper.assertTrue(KnowledgeService.data(player).learned().contains(LoreFixture.GHOST), "the record keeps the unloaded id");
        } finally {
            LoreFixture.install(true);
        }
        helper.assertTrue(KnowledgeService.knows(player.getUUID(), LoreFixture.GHOST), "known again once the mod is back");
        logout(player);
        helper.succeed();
    }

    private static void conditionProvider(GameTestHelper helper) {
        String key = "sanctum";
        helper.assertFalse(Conditions.hasProvider(key), "no subject mod may provide sanctum in the framework test server");
        LoreCondition condition = LoreCondition.parse("sanctum>=40");
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(TABLE);
        helper.assertFalse(Conditions.test(level, pos, condition), "without a provider the condition never holds");
        try {
            LoreApi.registerCondition(key, (l, p) -> 50);
            helper.assertTrue(Conditions.test(level, pos, condition), "provider value 50 meets sanctum>=40");
            LoreApi.registerCondition(key, (l, p) -> 0);
            helper.assertTrue(Conditions.test(level, pos, condition), "a second registration keeps the first provider");
        } finally {
            Conditions.unregisterForTest(key);
        }
        helper.succeed();
    }

    private static void ensureListener() {
        if (LISTENER.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(LoreLearnedEvent.class, event -> {
                List<LoreLearnedEvent> list = EVENTS.get(event.player().getUUID());
                if (list != null) {
                    list.add(event);
                }
            });
        }
    }

    private static void learnedEvent(GameTestHelper helper) {
        ensureListener();
        ensureMigrator();
        ServerPlayer player = player(helper);
        List<LoreLearnedEvent> events = new CopyOnWriteArrayList<>();
        EVENTS.put(player.getUUID(), events);
        ItemStack literature = notes(LoreFixture.BASIC);
        player.setItemInHand(InteractionHand.MAIN_HAND, literature.copy());
        StudyResult result = StudyService.beginPolled(player, player.getMainHandItem(), 1.0, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.started(), "study starts: " + result.failCode());
        helper.onEachTick(() -> pokeStudy(player));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(KnowledgeService.data(player).learned().contains(LoreFixture.BASIC), "studied"))
                .thenExecute(() -> {
                    KnowledgeService.grantAll(player, List.of(LoreFixture.ADVANCED));
                    MIGRATE_FOR.add(player.getUUID());
                    Migrations.rerun(player);
                    MIGRATE_FOR.remove(player.getUUID());
                    EVENTS.remove(player.getUUID());
                    helper.assertValueEqual(events.size(), 3, "one event per learned node");
                    LoreLearnedEvent study = events.get(0);
                    helper.assertTrue(study.source() == LoreLearnedEvent.Source.STUDY && study.node().equals(LoreFixture.BASIC)
                            && ItemStack.isSameItemSameComponents(study.literature(), literature), "study event with its literature");
                    LoreLearnedEvent grant = events.get(1);
                    helper.assertTrue(grant.source() == LoreLearnedEvent.Source.GRANT && grant.node().equals(LoreFixture.ADVANCED)
                            && grant.literature().isEmpty(), "grant event without literature");
                    LoreLearnedEvent migrate = events.get(2);
                    helper.assertTrue(migrate.source() == LoreLearnedEvent.Source.MIGRATE && migrate.node().equals(LoreFixture.MIGRATED)
                            && migrate.literature().isEmpty(), "migration event without literature");
                    logout(player);
                })
                .thenSucceed();
    }

    // ---- data ----

    private static void carrierFilter(GameTestHelper helper) {
        List<CarrierFormRegistry.Form> loaded = CarrierFormRegistry.forms();
        List<CarrierFormRegistry.Form> next = new ArrayList<>(loaded);
        next.add(new CarrierFormRegistry.Form(new CarrierForms.Decl("earth_lore:blank_notes", "minecraft:writable_book", null,
                Set.of(LoreFixture.FILTERED.toString())), 1.0));
        CarrierFormRegistry.replaceForTest(next);
        try {
            Item carrier = LoreContent.BLANK_NOTES.get();
            helper.assertValueEqual(CarrierFormRegistry.resolve(carrier, LoreFixture.GEO, List.of(LoreFixture.FILTERED)).orElse(null),
                    Items.WRITABLE_BOOK, "the node-filtered declaration wins");
            helper.assertValueEqual(CarrierFormRegistry.resolve(carrier, LoreFixture.GEO, List.of(LoreFixture.BASIC)).orElse(null),
                    LoreContent.RESEARCH_NOTES.get(), "other nodes use the unfiltered declaration");
        } finally {
            CarrierFormRegistry.replaceForTest(loaded);
        }
        helper.succeed();
    }

    private static void teachingMerge(GameTestHelper helper) {
        Registry<VillagerProfession> registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);
        Either<TagKey<VillagerProfession>, ResourceKey<VillagerProfession>> cleric = Either.right(VillagerProfession.CLERIC);
        List<Teaching> firstMod = List.of(new Teaching(LoreFixture.TEACH_A, Optional.empty(), Optional.empty(), 3));
        List<Teaching> secondMod = List.of(new Teaching(LoreFixture.TEACH_B, Optional.empty(), Optional.empty(), 3),
                new Teaching(LoreFixture.TEACH_A, Optional.of(20), Optional.empty(), 3));
        List<Teaching> merged = Teaching.<VillagerProfession>merger().merge(registry, cleric, firstMod, cleric, secondMod);
        helper.assertValueEqual(merged.stream().map(Teaching::node).toList(), List.of(LoreFixture.TEACH_A, LoreFixture.TEACH_B),
                "both lists are kept and a repeated node appears once");
        helper.assertTrue(merged.get(0).price().isEmpty(), "the earlier entry for a node wins");
        List<MerchantOffer> offers = TeachingService.offersFor(merged, "minecraft", 2);
        helper.assertValueEqual(offers.size(), 2, "both trades are offered at level 2");
        Set<Identifier> sold = new HashSet<>();
        for (MerchantOffer offer : offers) {
            LoreText text = offer.getResult().get(LoreContent.LORE_TEXT.get());
            if (text != null) {
                sold.addAll(text.nodes());
            }
        }
        helper.assertTrue(sold.containsAll(List.of(LoreFixture.TEACH_A, LoreFixture.TEACH_B)), "the trades sell notes for both nodes");
        helper.succeed();
    }

    // ---- scope ----

    private static void teamScoreboard(GameTestHelper helper) {
        ServerPlayer a = player(helper);
        ServerPlayer b = player(helper);
        ServerScoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam("lore_t" + NAMES.incrementAndGet());
        scoreboard.addPlayerToTeam(a.getScoreboardName(), team);
        scoreboard.addPlayerToTeam(b.getScoreboardName(), team);
        TeamTracker.markChanged();
        KnowledgeService.grantAll(a, List.of(LoreFixture.BASIC));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC), "a teammate's node counts"))
                .thenExecute(() -> {
                    helper.assertFalse(KnowledgeService.data(b).learned().contains(LoreFixture.BASIC), "B's own record is unchanged");
                    scoreboard.removePlayerFromTeam(b.getScoreboardName(), team);
                    TeamTracker.markChanged();
                })
                .thenWaitUntil(() -> helper.assertFalse(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC),
                        "after leaving the team the node no longer counts"))
                .thenExecute(() -> {
                    scoreboard.removePlayerTeam(team);
                    logout(a);
                    logout(b);
                })
                .thenSucceed();
    }

    private static void scopeIndex(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameProfile profileA = new GameProfile(UUID.randomUUID(), name());
        ServerPlayer a = login(helper, profileA);
        ServerPlayer b = player(helper);
        KnowledgeService.grantAll(a, List.of(LoreFixture.BASIC));
        helper.assertTrue(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC), "server scope: an online player's node counts");
        logout(a);
        KnowledgeService.invalidate();
        helper.assertTrue(KnowledgeIndex.get(server).nodes(profileA.id()).contains(LoreFixture.BASIC), "the index mirrors A after logout");
        helper.assertTrue(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC), "server scope: an offline player counts through the index");

        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam("lore_t" + NAMES.incrementAndGet());
        scoreboard.addPlayerToTeam(profileA.name(), team);
        scoreboard.addPlayerToTeam(b.getScoreboardName(), team);
        LoreConfig.scopeOverride = LoreConfig.Scope.TEAM;
        TeamTracker.resolve();
        KnowledgeService.invalidate();
        try {
            helper.assertTrue(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC), "team scope: an offline teammate counts through the index");
        } finally {
            LoreConfig.scopeOverride = LoreConfig.Scope.SERVER;
            KnowledgeService.invalidate();
            scoreboard.removePlayerTeam(team);
        }

        KnowledgeIndex.get(server).put(profileA.id(), profileA.name(), Set.of(LoreFixture.ADVANCED));
        ServerPlayer back = login(helper, profileA);
        Set<Identifier> attachment = Set.copyOf(KnowledgeService.data(back).learned());
        helper.assertTrue(attachment.contains(LoreFixture.BASIC), "the attachment survives the relog");
        helper.assertValueEqual(Set.copyOf(KnowledgeIndex.get(server).nodes(profileA.id())), attachment,
                "logging in replaces the index entry with the attachment");
        logout(back);
        logout(b);
        helper.succeed();
    }

    // ---- framework ----

    /** One batch per scope: installs the fixture graph and the scope, then restores the data-pack state. */
    record LoreEnvironment(String scope, String teamSource) implements TestEnvironmentDefinition<Unit> {
        static final MapCodec<LoreEnvironment> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("scope").forGetter(LoreEnvironment::scope),
                Codec.STRING.optionalFieldOf("team_source", "auto").forGetter(LoreEnvironment::teamSource)
        ).apply(instance, LoreEnvironment::new));

        @Override
        public Unit setup(ServerLevel level) {
            LoreFixture.install(true);
            LoreConfig.scopeOverride = LoreConfig.Scope.valueOf(scope.toUpperCase(Locale.ROOT));
            LoreConfig.teamSourceOverride = LoreConfig.TeamSourceOption.valueOf(teamSource.toUpperCase(Locale.ROOT));
            TeamTracker.resolve();
            KnowledgeService.invalidate();
            return Unit.INSTANCE;
        }

        @Override
        public void teardown(ServerLevel level, Unit saved) {
            LoreConfig.scopeOverride = null;
            LoreConfig.teamSourceOverride = null;
            TeamTracker.resolve();
            KnowledgeService.revalidate();
        }

        @Override
        public MapCodec<LoreEnvironment> codec() {
            return CODEC;
        }
    }

    static final class Direct extends GameTestInstance {
        static final MapCodec<Direct> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("test").forGetter(test -> test.name),
                TestData.CODEC.forGetter(Direct::info)
        ).apply(instance, Direct::new));

        private final String name;

        Direct(String name, TestData<Holder<TestEnvironmentDefinition<?>>> info) {
            super(info);
            this.name = name;
        }

        @Override
        public void run(GameTestHelper helper) {
            Consumer<GameTestHelper> test = TESTS.get(name);
            if (test == null) {
                throw new IllegalStateException("Unknown earth_lore GameTest: " + name);
            }
            test.accept(helper);
        }

        @Override
        public MapCodec<Direct> codec() {
            return CODEC;
        }

        @Override
        protected MutableComponent typeDescription() {
            return Component.literal("earth_lore GameTest");
        }
    }
}

