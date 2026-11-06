package com.xxsx.earthlore.network;

import com.xxsx.earthlore.EarthLore;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Payloads of 10 册 A-017, plus the study-progress notice used by the HUD. */
public final class LorePayloads {
    private static final StreamCodec<ByteBuf, List<Identifier>> ID_LIST =
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(64));

    private LorePayloads() {
    }

    /** Graph hash plus rejected, personal and effective bitmaps over the sorted node order. */
    public record SyncFull(int hash, long[] rejected, long[] personal, long[] effective) implements CustomPacketPayload {
        public static final Type<SyncFull> TYPE = new Type<>(EarthLore.id("lore_sync_full"));
        public static final StreamCodec<ByteBuf, SyncFull> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, SyncFull::hash,
                ByteBufCodecs.LONG_ARRAY, SyncFull::rejected,
                ByteBufCodecs.LONG_ARRAY, SyncFull::personal,
                ByteBufCodecs.LONG_ARRAY, SyncFull::effective,
                SyncFull::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncDelta(List<Identifier> personalAdd, List<Identifier> personalRemove,
                            List<Identifier> effectiveAdd, List<Identifier> effectiveRemove) implements CustomPacketPayload {
        public static final Type<SyncDelta> TYPE = new Type<>(EarthLore.id("lore_sync_delta"));
        public static final StreamCodec<ByteBuf, SyncDelta> CODEC = StreamCodec.composite(
                ID_LIST, SyncDelta::personalAdd,
                ID_LIST, SyncDelta::personalRemove,
                ID_LIST, SyncDelta::effectiveAdd,
                ID_LIST, SyncDelta::effectiveRemove,
                SyncDelta::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Study progress for the HUD; an empty node means studying stopped. */
    public record StudyState(Optional<Identifier> node, int total) implements CustomPacketPayload {
        public static final Type<StudyState> TYPE = new Type<>(EarthLore.id("lore_study_state"));
        public static final StreamCodec<ByteBuf, StudyState> CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), StudyState::node,
                ByteBufCodecs.VAR_INT, StudyState::total,
                StudyState::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResearchStart(BlockPos pos, int mode, Optional<Identifier> target) implements CustomPacketPayload {
        public static final Type<ResearchStart> TYPE = new Type<>(EarthLore.id("lore_research_start"));
        public static final StreamCodec<ByteBuf, ResearchStart> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ResearchStart::pos,
                ByteBufCodecs.VAR_INT, ResearchStart::mode,
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), ResearchStart::target,
                ResearchStart::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResearchCancel(BlockPos pos) implements CustomPacketPayload {
        public static final Type<ResearchCancel> TYPE = new Type<>(EarthLore.id("lore_research_cancel"));
        public static final StreamCodec<ByteBuf, ResearchCancel> CODEC = BlockPos.STREAM_CODEC
                .map(ResearchCancel::new, ResearchCancel::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent by a client whose node order hash differs from the server's. */
    public record SyncRequest(int clientHash) implements CustomPacketPayload {
        public static final Type<SyncRequest> TYPE = new Type<>(EarthLore.id("lore_sync_request"));
        public static final StreamCodec<ByteBuf, SyncRequest> CODEC = ByteBufCodecs.INT
                .map(SyncRequest::new, SyncRequest::clientHash);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

