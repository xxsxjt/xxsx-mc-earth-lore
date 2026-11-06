package com.xxsx.earthlore.research;

import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.api.StudyResult;
import com.xxsx.earthlore.condition.Conditions;
import com.xxsx.earthlore.core.Allocation;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.core.StudyMath;
import com.xxsx.earthlore.data.CarrierFormRegistry;
import com.xxsx.earthlore.data.ItemReq;
import com.xxsx.earthlore.data.LoreCondition;
import com.xxsx.earthlore.data.LoreIngredient;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.data.ResearchCost;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.registry.LoreSounds;
import com.xxsx.earthlore.registry.LoreTags;
import com.xxsx.earthlore.study.LiteratureItem;
import com.xxsx.earthlore.study.StudyService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Research table state machine of 10 册 A-005: research, copy and compile. Materials and the carrier are taken in
 * one step when a job starts and returned in full when it is cancelled; the product is computed at start and
 * appears in the output slot when the job finishes. Finishing research never grants knowledge: the notes must
 * be studied like any other literature.
 */
public class ResearchTableBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    public static final int SLOT_CARRIER = 0;
    public static final int SLOT_MATERIAL_FIRST = 1;
    public static final int MATERIAL_SLOTS = 8;
    public static final int SLOT_INSTRUMENT_FIRST = 9;
    public static final int INSTRUMENT_SLOTS = 2;
    public static final int SLOT_OUTPUT = 11;
    public static final int SLOT_COUNT = 12;

    public static final int MODE_RESEARCH = 0;
    public static final int MODE_COPY = 1;
    public static final int MODE_COMPILE = 2;

    public static final int DATA_MODE = 0;
    public static final int DATA_PROGRESS = 1;
    public static final int DATA_TOTAL = 2;
    public static final int DATA_STATE = 3;
    public static final int DATA_PAUSE = 4;
    public static final int DATA_TARGET = 5;
    public static final int DATA_FAIL = 6;
    public static final int DATA_COUNT = 7;

    public static final int MAX_TICKS = 24000;
    private static final int STEP = 20;

    public enum State {
        IDLE, RUNNING, PAUSED, DONE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static State byId(String id) {
            for (State state : values()) {
                if (state.id().equals(id)) {
                    return state;
                }
            }
            return IDLE;
        }

        public static State byOrdinal(int ordinal) {
            State[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
        }
    }

    /** Message codes sent through menu data; index 0 means no message. */
    public static final List<String> MESSAGES = messages();

    private static final int[] SLOTS_DOWN = {SLOT_OUTPUT};
    private static final int[] SLOTS_INPUT = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private UUID owner;
    private String ownerName = "";
    private int mode = MODE_RESEARCH;
    private Identifier target;
    private int progress;
    private int total;
    private State state = State.IDLE;
    private String pauseReason = "";
    private String lastFail = "";
    private List<Identifier> lastMissing = List.of();
    private ItemStack pending = ItemStack.EMPTY;
    private final List<ItemStack> consumed = new ArrayList<>();
    private int stepTimer;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_MODE -> mode;
                case DATA_PROGRESS -> progress;
                case DATA_TOTAL -> total;
                case DATA_STATE -> state.ordinal();
                case DATA_PAUSE -> Math.max(0, MESSAGES.indexOf(pauseReason));
                case DATA_TARGET -> target == null ? -1 : KnowledgeService.graph().index(target);
                case DATA_FAIL -> Math.max(0, MESSAGES.indexOf(lastFail));
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public ResearchTableBlockEntity(BlockPos pos, BlockState state) {
        super(LoreContent.RESEARCH_TABLE_BE.get(), pos, state);
    }

    private static List<String> messages() {
        List<String> out = new ArrayList<>(List.of("", FailCodes.PREREQ_MISSING, FailCodes.ITEM_MISSING,
                FailCodes.COST_MISSING, FailCodes.CARRIER_INVALID, FailCodes.ALREADY_KNOWN, FailCodes.UNKNOWN_NODE,
                FailCodes.TABLE_BUSY, FailCodes.TEXT_EMPTY, FailCodes.TREATISE_MIXED, FailCodes.TREATISE_FULL));
        for (LoreCondition.Kind kind : LoreCondition.Kind.values()) {
            if (!out.contains(kind.failCode())) {
                out.add(kind.failCode());
            }
        }
        return List.copyOf(out);
    }

    public static boolean isCarrierSlotItem(ItemStack stack) {
        return stack.is(LoreTags.CARRIERS) || stack.is(Items.BOOK);
    }

    public ContainerData data() {
        return data;
    }

    public State state() {
        return state;
    }

    public int mode() {
        return mode;
    }

    public int progress() {
        return progress;
    }

    public int total() {
        return total;
    }

    public Optional<Identifier> target() {
        return Optional.ofNullable(target);
    }

    public Optional<UUID> owner() {
        return Optional.ofNullable(owner);
    }

    public String pauseReason() {
        return pauseReason;
    }

    public String lastFail() {
        return lastFail;
    }

    /** Product of the running job; empty when idle. */
    public ItemStack pending() {
        return pending.copy();
    }

    public int consumedStacks() {
        return consumed.size();
    }

    // ---- starting ----

    /** Handles lore_research_start; failures leave the table idle and are reported to the player (A-005 on_fail). */
    public void start(ServerPlayer player, int requestedMode, Identifier requestedTarget) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        lastMissing = List.of();
        String fail;
        if (state != State.IDLE || !items.get(SLOT_OUTPUT).isEmpty()) {
            fail = FailCodes.TABLE_BUSY;
        } else {
            fail = switch (requestedMode) {
                case MODE_RESEARCH -> startResearch(serverLevel, player, requestedTarget);
                case MODE_COPY -> startCopy();
                case MODE_COMPILE -> startCompile(player);
                default -> FailCodes.UNKNOWN_NODE;
            };
        }
        if (fail != null) {
            lastFail = fail;
            player.sendOverlayMessage(StudyService.failMessage(StudyResult.fail(fail, lastMissing)));
            LoreSounds.locked(player);
            setChanged();
            return;
        }
        lastFail = "";
        owner = player.getUUID();
        ownerName = player.getScoreboardName();
        progress = 0;
        stepTimer = 0;
        pauseReason = "";
        state = State.RUNNING;
        serverLevel.playSound(null, worldPosition, LoreSounds.RESEARCH_RUNNING.value(), SoundSource.BLOCKS, 0.6F, 1.0F);
        setChanged();
    }

    private String startResearch(ServerLevel level, ServerPlayer player, Identifier targetId) {
        LoreGraph graph = KnowledgeService.graph();
        Optional<LoreGraph.Node> found = targetId == null ? Optional.empty() : graph.node(targetId);
        if (found.isEmpty()) {
            return FailCodes.UNKNOWN_NODE;
        }
        LoreGraph.Node node = found.get();
        if (KnowledgeService.knowsSelf(player, node.id())) {
            return FailCodes.ALREADY_KNOWN;
        }
        List<Identifier> missing = new ArrayList<>();
        if (!KnowledgeService.prerequisitesMet(player.getUUID(), node, missing)) {
            lastMissing = List.copyOf(missing);
            return FailCodes.PREREQ_MISSING;
        }
        for (ItemReq req : node.data().itemRequires()) {
            if (req.hold().isPresent() && !holds(player, req.hold().get())) {
                return FailCodes.ITEM_MISSING;
            }
        }
        for (ItemReq req : node.data().itemRequires()) {
            if (req.made().isPresent() && !made(player, req.made().get())) {
                return FailCodes.ITEM_MISSING;
            }
        }
        String condition = failedCondition(level, node);
        if (condition != null) {
            return condition;
        }
        ResearchCost cost = node.data().research();
        int[][] take = allocate(cost);
        if (take == null) {
            return FailCodes.COST_MISSING;
        }
        ItemStack carrier = items.get(SLOT_CARRIER);
        Optional<Item> written = carrier.isEmpty() || !carrier.is(LoreTags.CARRIERS) ? Optional.empty()
                : CarrierFormRegistry.resolve(carrier.getItem(), node.discipline(), List.of(node.id()));
        if (written.isEmpty()) {
            return FailCodes.CARRIER_INVALID;
        }
        consumed.clear();
        consumed.add(carrier.split(1));
        for (int r = 0; r < take.length; r++) {
            for (int s = 0; s < MATERIAL_SLOTS; s++) {
                if (take[r][s] > 0) {
                    consumed.add(items.get(SLOT_MATERIAL_FIRST + s).split(take[r][s]));
                }
            }
        }
        normalize();
        ItemStack out = new ItemStack(written.get());
        out.set(LoreContent.LORE_TEXT.get(), new LoreText(List.of(node.id()), node.discipline(),
                LiteratureItem.formOf(written.get()), Optional.of(player.getUUID()), player.getScoreboardName(), 0));
        pending = out;
        mode = MODE_RESEARCH;
        target = node.id();
        total = clampTicks(StudyMath.tableTicks(cost.ticks(), LoreConfig.researchSpeed()));
        return null;
    }

    private String startCopy() {
        ItemStack original = items.get(SLOT_MATERIAL_FIRST);
        LoreText text = original.get(LoreContent.LORE_TEXT.get());
        if (text == null || text.nodes().isEmpty()) {
            return FailCodes.TEXT_EMPTY;
        }
        ItemStack ink = items.get(SLOT_MATERIAL_FIRST + 1);
        if (!ink.is(Items.INK_SAC)) {
            return FailCodes.COST_MISSING;
        }
        ItemStack carrier = items.get(SLOT_CARRIER);
        if (!copyCarrier(carrier, original, text)) {
            return FailCodes.CARRIER_INVALID;
        }
        consumed.clear();
        consumed.add(carrier.split(1));
        consumed.add(ink.split(1));
        normalize();
        ItemStack copy = original.copyWithCount(1);
        copy.set(LoreContent.LORE_TEXT.get(), text.copied());
        pending = copy;
        mode = MODE_COPY;
        target = text.nodes().get(0);
        total = clampTicks(StudyMath.tableTicks(StudyMath.copyTicks(text.nodes().size()), LoreConfig.researchSpeed()));
        return null;
    }

    /** A treatise is copied onto a book; other forms onto a carrier whose written form is the original's item. */
    static boolean copyCarrier(ItemStack carrier, ItemStack original, LoreText text) {
        if (carrier.isEmpty()) {
            return false;
        }
        if (original.is(LoreContent.TREATISE.get())) {
            return carrier.is(Items.BOOK);
        }
        if (!carrier.is(LoreTags.CARRIERS)) {
            return false;
        }
        return CarrierFormRegistry.resolve(carrier.getItem(), text.discipline(), text.nodes())
                .map(item -> item == original.getItem()).orElse(false);
    }

    private String startCompile(ServerPlayer player) {
        LoreGraph graph = KnowledgeService.graph();
        Identifier discipline = null;
        int count = 0;
        Map<Identifier, Integer> slotOf = new LinkedHashMap<>();
        for (int s = 0; s < MATERIAL_SLOTS; s++) {
            ItemStack stack = items.get(SLOT_MATERIAL_FIRST + s);
            if (stack.isEmpty()) {
                continue;
            }
            LoreText text = stack.get(LoreContent.LORE_TEXT.get());
            if (text == null || text.nodes().size() != 1) {
                return FailCodes.TEXT_EMPTY;
            }
            count++;
            if (discipline == null) {
                discipline = text.discipline();
            } else if (!discipline.equals(text.discipline())) {
                return FailCodes.TREATISE_MIXED;
            }
            slotOf.putIfAbsent(text.nodes().get(0), s);
        }
        if (count > LoreText.MAX_NODES) {
            return FailCodes.TREATISE_FULL;
        }
        if (slotOf.size() < 2) {
            return FailCodes.TEXT_EMPTY;
        }
        ItemStack book = items.get(SLOT_CARRIER);
        if (!book.is(Items.BOOK)) {
            return FailCodes.CARRIER_INVALID;
        }
        List<Identifier> nodes = studyOrder(graph, slotOf.keySet());
        consumed.clear();
        consumed.add(book.split(1));
        for (int s : slotOf.values()) {
            consumed.add(items.get(SLOT_MATERIAL_FIRST + s).split(1));
        }
        normalize();
        ItemStack treatise = new ItemStack(LoreContent.TREATISE.get());
        treatise.set(LoreContent.LORE_TEXT.get(), new LoreText(nodes, discipline, LoreText.FORM_TREATISE,
                Optional.of(player.getUUID()), player.getScoreboardName(), 0));
        pending = treatise;
        mode = MODE_COMPILE;
        target = nodes.get(0);
        total = clampTicks(StudyMath.tableTicks(StudyMath.compileTicks(nodes.size()), LoreConfig.researchSpeed()));
        return null;
    }

    /** Tier, then id: a topological order because every prerequisite has a lower tier (A-004 rule 1). */
    public static List<Identifier> studyOrder(LoreGraph graph, Collection<Identifier> ids) {
        List<Identifier> out = new ArrayList<>(ids);
        out.sort(Comparator.comparingInt((Identifier id) -> graph.node(id).map(LoreGraph.Node::tier).orElse(Integer.MAX_VALUE))
                .thenComparing(Identifier::toString));
        return out;
    }

    private boolean holds(ServerPlayer player, LoreIngredient ingredient) {
        for (int i = 0; i < INSTRUMENT_SLOTS; i++) {
            if (ingredient.test(items.get(SLOT_INSTRUMENT_FIRST + i))) {
                return true;
            }
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (ingredient.test(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean made(ServerPlayer player, Identifier itemId) {
        return BuiltInRegistries.ITEM.getOptional(itemId)
                .map(item -> player.getStats().getValue(Stats.ITEM_CRAFTED.get(item)) >= 1)
                .orElse(false);
    }

    private String failedCondition(ServerLevel level, LoreGraph.Node node) {
        for (ItemReq req : node.data().itemRequires()) {
            if (req.cond().isPresent() && !Conditions.test(level, worldPosition, req.cond().get())) {
                return req.cond().get().failCode();
            }
        }
        return null;
    }

    private int[][] allocate(ResearchCost cost) {
        List<ResearchCost.Material> materials = cost.items();
        int[] slotCounts = new int[MATERIAL_SLOTS];
        for (int s = 0; s < MATERIAL_SLOTS; s++) {
            slotCounts[s] = items.get(SLOT_MATERIAL_FIRST + s).getCount();
        }
        int[] required = new int[materials.size()];
        boolean[][] accepts = new boolean[materials.size()][MATERIAL_SLOTS];
        for (int r = 0; r < materials.size(); r++) {
            required[r] = materials.get(r).count();
            for (int s = 0; s < MATERIAL_SLOTS; s++) {
                accepts[r][s] = materials.get(r).ingredient().test(items.get(SLOT_MATERIAL_FIRST + s));
            }
        }
        return Allocation.solve(slotCounts, required, accepts);
    }

    private static int clampTicks(int ticks) {
        return Math.max(STEP, Math.min(MAX_TICKS, ticks));
    }

    private void normalize() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }
    }

    // ---- running ----

    /** Every 20 ticks: research re-checks its conditions (pausing when one fails) and progress grows by 20. */
    public void serverTick(ServerLevel level) {
        if (state == State.DONE) {
            if (items.get(SLOT_OUTPUT).isEmpty()) {
                toIdle();
                setChanged();
            }
            return;
        }
        if (state == State.IDLE) {
            return;
        }
        if (state == State.RUNNING && level.getGameTime() % LoreSounds.RUNNING_INTERVAL == 0) {
            level.playSound(null, worldPosition, LoreSounds.RESEARCH_RUNNING.value(), SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        if (++stepTimer < STEP) {
            return;
        }
        stepTimer = 0;
        if (mode == MODE_RESEARCH && target != null) {
            String failed = KnowledgeService.graph().node(target).map(node -> failedCondition(level, node)).orElse(null);
            if (failed != null) {
                if (state != State.PAUSED || !failed.equals(pauseReason)) {
                    state = State.PAUSED;
                    pauseReason = failed;
                    level.playSound(null, worldPosition, LoreSounds.LOCKED.value(), SoundSource.BLOCKS, 0.5F, 1.0F);
                    setChanged();
                }
                return;
            }
            if (state == State.PAUSED) {
                state = State.RUNNING;
                pauseReason = "";
            }
        }
        progress = Math.min(total, progress + STEP);
        if (progress >= total) {
            complete(level);
        }
        setChanged();
    }

    private void complete(ServerLevel level) {
        ItemStack out = pending;
        pending = ItemStack.EMPTY;
        consumed.clear();
        if (items.get(SLOT_OUTPUT).isEmpty()) {
            items.set(SLOT_OUTPUT, out);
        } else {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                    worldPosition.getZ() + 0.5, out);
        }
        state = State.DONE;
        level.playSound(null, worldPosition, LoreSounds.RESEARCH_DONE.value(), SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    /** lore_research_cancel: returns every taken item to the table, dropping what does not fit. */
    public void cancel(ServerPlayer player) {
        if (state != State.RUNNING && state != State.PAUSED) {
            return;
        }
        refund();
        pending = ItemStack.EMPTY;
        lastFail = "";
        toIdle();
        setChanged();
    }

    private void toIdle() {
        state = State.IDLE;
        progress = 0;
        total = 0;
        target = null;
        pauseReason = "";
        stepTimer = 0;
    }

    private void refund() {
        for (int i = 0; i < consumed.size(); i++) {
            ItemStack stack = consumed.get(i).copy();
            if (i == 0) {
                stack = insert(stack, SLOT_CARRIER, SLOT_CARRIER + 1);
            }
            stack = insert(stack, SLOT_MATERIAL_FIRST, SLOT_MATERIAL_FIRST + MATERIAL_SLOTS);
            if (!stack.isEmpty() && level != null) {
                Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                        worldPosition.getZ() + 0.5, stack);
            }
        }
        consumed.clear();
    }

    private ItemStack insert(ItemStack stack, int from, int to) {
        for (int s = from; s < to && !stack.isEmpty(); s++) {
            ItemStack existing = items.get(s);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int room = Math.min(existing.getMaxStackSize(), getMaxStackSize()) - existing.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                }
            }
        }
        for (int s = from; s < to && !stack.isEmpty(); s++) {
            if (items.get(s).isEmpty()) {
                items.set(s, stack);
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        if (level != null && (state == State.RUNNING || state == State.PAUSED)) {
            for (ItemStack stack : consumed) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack.copy());
            }
            consumed.clear();
        }
        super.preRemoveSideEffects(pos, oldState);
    }

    // ---- container ----

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> newItems) {
        this.items = newItems;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_OUTPUT) {
            return false;
        }
        return slot != SLOT_CARRIER || isCarrierSlotItem(stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return side == Direction.DOWN ? SLOTS_DOWN.clone() : SLOTS_INPUT.clone();
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot == SLOT_OUTPUT;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.earth_lore.research_table");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new ResearchTableMenu(containerId, inventory, this, data);
    }

    // ---- persistence ----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.storeNullable("owner_uuid", UUIDUtil.CODEC, owner);
        output.putString("owner_name", ownerName);
        output.putInt("mode", mode);
        output.putString("target_node", target == null ? "" : target.toString());
        output.putInt("progress", progress);
        output.putInt("total", total);
        output.putString("state", state.id());
        output.putString("pause_reason", pauseReason);
        output.storeNullable("pending", ItemStack.CODEC, pending.isEmpty() ? null : pending);
        ValueOutput.TypedOutputList<ItemStack> list = output.list("consumed", ItemStack.CODEC);
        for (ItemStack stack : consumed) {
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        owner = input.read("owner_uuid", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        mode = Math.max(MODE_RESEARCH, Math.min(MODE_COMPILE, input.getIntOr("mode", MODE_RESEARCH)));
        String rawTarget = input.getStringOr("target_node", "");
        target = rawTarget.isEmpty() ? null : Identifier.tryParse(rawTarget);
        total = Math.max(0, Math.min(MAX_TICKS, input.getIntOr("total", 0)));
        progress = Math.max(0, Math.min(total, input.getIntOr("progress", 0)));
        state = State.byId(input.getStringOr("state", State.IDLE.id()));
        pauseReason = input.getStringOr("pause_reason", "");
        pending = input.read("pending", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        consumed.clear();
        input.listOrEmpty("consumed", ItemStack.CODEC).forEach(consumed::add);
    }
}
