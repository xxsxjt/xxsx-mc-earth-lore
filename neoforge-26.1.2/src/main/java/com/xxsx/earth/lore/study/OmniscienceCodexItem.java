package com.xxsx.earth.lore.study;

import com.xxsx.earth.lore.knowledge.KnowledgeService;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Creative-only debug/sandbox item that unlocks every loaded knowledge node across all disciplines in one click.
 */
public final class OmniscienceCodexItem extends Item {
    public OmniscienceCodexItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!player.isCreative()) {
            if (!level.isClientSide()) {
                player.sendOverlayMessage(Component.translatable("message.earth_lore.omniscience_codex.creative_only")
                        .withStyle(ChatFormatting.RED));
            }
            return InteractionResult.FAIL;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            List<Identifier> allNodes = KnowledgeService.graph().nodes().stream()
                    .map(com.xxsx.earth.lore.graph.LoreGraph.Node::id)
                    .toList();
            List<Identifier> added = KnowledgeService.grantAll(serverPlayer, allNodes);
            if (added.isEmpty()) {
                serverPlayer.sendSystemMessage(Component.translatable("message.earth_lore.omniscience_codex.already_all",
                        allNodes.size()).withStyle(ChatFormatting.AQUA));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.earth_lore.omniscience_codex.unlocked",
                        added.size(), allNodes.size()).withStyle(ChatFormatting.GOLD));
            }
            ServerLevel serverLevel = serverPlayer.level();
            serverLevel.playSound(null, serverPlayer.blockPosition(), SoundEvents.PLAYER_LEVELUP,
                    SoundSource.PLAYERS, 0.85F, 1.1F);
            serverLevel.sendParticles(ParticleTypes.ENCHANT,
                    serverPlayer.getX(), serverPlayer.getY() + 1.2D, serverPlayer.getZ(),
                    32, 0.7D, 0.6D, 0.7D, 0.25D);
            serverPlayer.swing(hand, true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable("tooltip.earth_lore.omniscience_codex.line1").withStyle(ChatFormatting.GOLD));
        lines.accept(Component.translatable("tooltip.earth_lore.omniscience_codex.line2").withStyle(ChatFormatting.GRAY));
    }
}
