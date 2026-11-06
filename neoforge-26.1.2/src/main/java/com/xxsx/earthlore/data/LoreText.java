package com.xxsx.earthlore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * earth_lore:lore_text (10 册 A-004). Nodes are kept in study order (tier, then id). The author UUID is
 * persisted but not sent to clients.
 */
public record LoreText(List<Identifier> nodes, Identifier discipline, String form, Optional<UUID> authorUuid,
                       String authorName, int copyGeneration) {
    public static final String FORM_NOTES = "notes";
    public static final String FORM_TREATISE = "treatise";
    public static final int MAX_NODES = 8;

    public LoreText {
        nodes = List.copyOf(nodes);
    }

    public static final Codec<LoreText> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.listOf(1, 8).fieldOf("nodes").forGetter(LoreText::nodes),
            Identifier.CODEC.fieldOf("discipline").forGetter(LoreText::discipline),
            Codec.STRING.optionalFieldOf("form", FORM_NOTES).forGetter(LoreText::form),
            UUIDUtil.CODEC.optionalFieldOf("author_uuid").forGetter(LoreText::authorUuid),
            Codec.string(0, 32).optionalFieldOf("author_name", "").forGetter(LoreText::authorName),
            Codec.intRange(0, 255).optionalFieldOf("copy_generation", 0).forGetter(LoreText::copyGeneration)
    ).apply(instance, LoreText::new));

    public static final StreamCodec<ByteBuf, LoreText> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(8)), LoreText::nodes,
            Identifier.STREAM_CODEC, LoreText::discipline,
            ByteBufCodecs.stringUtf8(128), LoreText::form,
            ByteBufCodecs.stringUtf8(32), LoreText::authorName,
            ByteBufCodecs.VAR_INT, LoreText::copyGeneration,
            (nodes, discipline, form, author, generation) ->
                    new LoreText(nodes, discipline, form, Optional.empty(), author, generation));

    public LoreText copied() {
        return new LoreText(nodes, discipline, form, authorUuid, authorName, Math.min(255, copyGeneration + 1));
    }

    public LoreText withForm(String newForm) {
        return new LoreText(nodes, discipline, newForm, authorUuid, authorName, copyGeneration);
    }
}

