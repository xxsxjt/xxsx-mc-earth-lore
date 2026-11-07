package com.xxsx.earthlore.study;

import com.xxsx.earthlore.api.StudyResult;
import com.xxsx.earthlore.data.CarrierFormRegistry;
import com.xxsx.earthlore.data.KnowledgeNode;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.registry.LoreContent;
import com.xxsx.earthlore.registry.LoreRegistries;
import com.xxsx.earthlore.teach.TeachingService;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * research_notes (one node) and treatise (2..8 nodes), 10 册 A-007/A-008. Hold use to study; studying never
 * consumes the literature. Knowledge-dependent tooltip lines are added client side.
 */
public class LiteratureItem extends Item {
    public static final int USE_DURATION = 72000;

    /** Installed by the client so use() fails locally when the text has nothing the player can study. */
    public static volatile BiPredicate<Player, ItemStack> clientCanStudy = (player, stack) -> true;

    private final boolean treatise;

    public LiteratureItem(Properties properties, boolean treatise) {
        super(properties);
        this.treatise = treatise;
    }

    public boolean treatise() {
        return treatise;
    }

    /** "notes", "treatise" or the item id of a discipline form (A-004 form). */
    public static String formOf(Item item) {
        if (item == LoreContent.RESEARCH_NOTES.get()) {
            return LoreText.FORM_NOTES;
        }
        if (item == LoreContent.TREATISE.get()) {
            return LoreText.FORM_TREATISE;
        }
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Written notes for one node in the form blank notes take for it (teaching, creative tab, tests). */
    public static ItemStack notesFor(Identifier node, Identifier discipline) {
        Item item = CarrierFormRegistry.resolve(LoreContent.BLANK_NOTES.get(), discipline, List.of(node))
                .orElse(LoreContent.RESEARCH_NOTES.get());
        ItemStack stack = new ItemStack(item);
        stack.set(LoreContent.LORE_TEXT.get(), new LoreText(List.of(node), discipline, formOf(item), Optional.empty(), "", 0));
        return stack;
    }

    /** Creative tab: notes for every non-root node in the synced registry. */
    public static void creativeNotes(HolderLookup.Provider holders, Consumer<ItemStack> output) {
        holders.lookup(LoreRegistries.KNOWLEDGE_NODE).ifPresent(lookup -> lookup.listElements()
                .filter(holder -> holder.value().discipline().isPresent() && !"root".equals(holder.value().gate().orElse("")))
                .sorted(Comparator.comparing((Holder.Reference<KnowledgeNode> holder) -> holder.value().discipline().get().toString())
                        .thenComparingInt(holder -> holder.value().tier().orElse(0))
                        .thenComparing(holder -> holder.key().identifier().toString()))
                .forEach(holder -> {
                    ItemStack stack = new ItemStack(LoreContent.RESEARCH_NOTES.get());
                    stack.set(LoreContent.LORE_TEXT.get(), new LoreText(List.of(holder.key().identifier()),
                            holder.value().discipline().get(), LoreText.FORM_NOTES, Optional.empty(), "", 0));
                    output.accept(stack);
                }));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.has(LoreContent.LORE_TEXT.get())) {
            return InteractionResult.FAIL;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            StudyResult result = StudyService.begin(serverPlayer, stack, CarrierFormRegistry.studyFactor(this), hand);
            if (!result.started()) {
                serverPlayer.sendOverlayMessage(StudyService.failMessage(result));
                return InteractionResult.FAIL;
            }
        } else if (!clientCanStudy.test(player, stack)) {
            return InteractionResult.FAIL;
        }
        player.startUsingItem(hand);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (stack.has(LoreContent.LORE_TEXT.get()) && (target instanceof Player || player.isShiftKeyDown())) {
            InteractionResult result = TeachingService.interactTeach(player, target, stack, hand);
            if (result.consumesAction()) {
                return InteractionResult.SUCCESS;
            }
        }
        return super.interactLivingEntity(stack, player, target, hand);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return USE_DURATION;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTime) {
        if (entity instanceof ServerPlayer player) {
            StudyService.cancel(player);
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        LoreText text = stack.get(LoreContent.LORE_TEXT.get());
        if (text == null) {
            builder.accept(Component.translatable("tooltip.earth_lore.literature.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!text.authorName().isEmpty()) {
            builder.accept(Component.translatable("tooltip.earth_lore.literature.author", text.authorName())
                    .withStyle(ChatFormatting.GRAY));
        }
        if (text.copyGeneration() > 0) {
            builder.accept(Component.translatable("tooltip.earth_lore.literature.copy", text.copyGeneration())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
