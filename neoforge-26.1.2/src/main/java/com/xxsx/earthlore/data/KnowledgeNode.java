package com.xxsx.earthlore.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xxsx.earthlore.core.Prereq;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Data-pack record of one knowledge node (10 册 A-002). Every field is optional so that a node with
 * missing data is rejected with a failure code during validation rather than failing the whole pack.
 */
public record KnowledgeNode(Optional<Identifier> discipline, Optional<Integer> tier, Optional<String> gate,
                            List<Prereq> requires, List<ItemReq> itemRequires, List<UnlockRef> unlocks,
                            List<String> literature, List<Identifier> teachers, ResearchCost research,
                            List<String> failCodes, Optional<String> nameKey, Optional<String> descKey) {
    public static final Codec<Prereq> PREREQ_CODEC = Codec.either(
            Identifier.CODEC.fieldOf("all").codec(),
            Identifier.CODEC.listOf().fieldOf("any").codec()
    ).xmap(either -> either.map(
                    all -> (Prereq) new Prereq.All(all.toString()),
                    any -> new Prereq.Any(any.stream().map(Identifier::toString).toList())),
            prereq -> prereq instanceof Prereq.All all
                    ? Either.left(Identifier.parse(all.node()))
                    : Either.right(prereq.members().stream().map(Identifier::parse).toList()));

    public static final Codec<KnowledgeNode> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.optionalFieldOf("discipline").forGetter(KnowledgeNode::discipline),
            Codec.INT.optionalFieldOf("tier").forGetter(KnowledgeNode::tier),
            Codec.STRING.optionalFieldOf("gate").forGetter(KnowledgeNode::gate),
            PREREQ_CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(KnowledgeNode::requires),
            ItemReq.CODEC.listOf().optionalFieldOf("item_requires", List.of()).forGetter(KnowledgeNode::itemRequires),
            UnlockRef.CODEC.listOf().optionalFieldOf("unlocks", List.of()).forGetter(KnowledgeNode::unlocks),
            Codec.STRING.listOf().optionalFieldOf("literature", List.of("notes")).forGetter(KnowledgeNode::literature),
            Identifier.CODEC.listOf().optionalFieldOf("teachers", List.of()).forGetter(KnowledgeNode::teachers),
            ResearchCost.CODEC.optionalFieldOf("research", ResearchCost.NONE).forGetter(KnowledgeNode::research),
            Codec.STRING.listOf().optionalFieldOf("fail_codes", List.of()).forGetter(KnowledgeNode::failCodes),
            Codec.STRING.optionalFieldOf("name_key").forGetter(KnowledgeNode::nameKey),
            Codec.STRING.optionalFieldOf("desc_key").forGetter(KnowledgeNode::descKey)
    ).apply(instance, KnowledgeNode::new));
}

