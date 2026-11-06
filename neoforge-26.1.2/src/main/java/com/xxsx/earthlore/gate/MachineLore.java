package com.xxsx.earthlore.gate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * earth_lore:machine block-entity attachment (10 册 A-013): owner, last knowledge snapshot and the
 * current lore fault. Only the fault is synced to clients, for screen overlays.
 */
public final class MachineLore {
    public static final int SNAPSHOT_LIMIT = 64;
    /** A-013 rule 2: an online owner's snapshot is refreshed at least this often while the machine is checked. */
    public static final long REFRESH_TICKS = 1200L;

    public static final MapCodec<MachineLore> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            UUIDUtil.CODEC.optionalFieldOf("lore_owner").forGetter(m -> Optional.ofNullable(m.owner)),
            Identifier.CODEC.listOf().optionalFieldOf("lore_snapshot", List.of()).forGetter(m -> List.copyOf(m.snapshot)),
            Codec.LONG.optionalFieldOf("lore_snapshot_tick", 0L).forGetter(m -> m.snapshotTick),
            Codec.STRING.optionalFieldOf("fault", "").forGetter(m -> m.fault),
            Identifier.CODEC.listOf().optionalFieldOf("fault_nodes", List.of()).forGetter(m -> m.faultNodes)
    ).apply(instance, MachineLore::new));

    public static final StreamCodec<ByteBuf, MachineLore> SYNC_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), m -> m.fault,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(16)), m -> m.faultNodes,
            (fault, nodes) -> new MachineLore(Optional.empty(), List.of(), 0L, fault, nodes));

    private UUID owner;
    private final LinkedHashSet<Identifier> snapshot = new LinkedHashSet<>();
    private long snapshotTick;
    private String fault = "";
    private List<Identifier> faultNodes = List.of();

    public MachineLore() {
    }

    private MachineLore(Optional<UUID> owner, List<Identifier> snapshot, long tick, String fault, List<Identifier> faultNodes) {
        this.owner = owner.orElse(null);
        this.snapshot.addAll(snapshot);
        this.snapshotTick = tick;
        this.fault = fault;
        this.faultNodes = List.copyOf(faultNodes);
    }

    public Optional<UUID> owner() {
        return Optional.ofNullable(owner);
    }

    void setOwner(UUID owner) {
        this.owner = owner;
        snapshot.clear();
        snapshotTick = 0;
    }

    public Set<Identifier> snapshot() {
        return java.util.Collections.unmodifiableSet(snapshot);
    }

    public long snapshotTick() {
        return snapshotTick;
    }

    /** Records which of the relevant nodes the online owner knows; true when the stored data changed. */
    boolean remember(List<Identifier> relevant, Set<Identifier> known, long tick) {
        boolean changed = false;
        for (Identifier id : relevant) {
            boolean want = known.contains(id);
            if (snapshot.contains(id) != want) {
                changed = true;
                if (want) {
                    snapshot.add(id);
                } else {
                    snapshot.remove(id);
                }
            }
        }
        Iterator<Identifier> it = snapshot.iterator();
        while (snapshot.size() > SNAPSHOT_LIMIT && it.hasNext()) {
            it.next();
            it.remove();
            changed = true;
        }
        if (changed || tick - snapshotTick >= REFRESH_TICKS) {
            snapshotTick = tick;
            return true;
        }
        return false;
    }

    public String fault() {
        return fault;
    }

    public List<Identifier> faultNodes() {
        return faultNodes;
    }

    /** @return true when the fault changed */
    boolean setFault(String code, List<Identifier> nodes) {
        List<Identifier> next = nodes.size() > 16 ? new ArrayList<>(nodes.subList(0, 16)) : nodes;
        if (code.equals(fault) && next.equals(faultNodes)) {
            return false;
        }
        fault = code;
        faultNodes = List.copyOf(next);
        return true;
    }
}

