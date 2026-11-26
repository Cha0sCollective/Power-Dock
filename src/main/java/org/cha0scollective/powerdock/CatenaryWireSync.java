package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = PowerDock.ID)
final class CatenaryWireSync {
    private static final Map<Level, ClientWires> CLIENT = new WeakHashMap<>();
    private final Map<Long, Map<UUID, CompoundTag>> chunks = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(ChunkWires.TYPE, ChunkWires.CODEC, (packet, context) -> {
            var level = context.player().level();
            if (level.isClientSide && packet.dimension.equals(level.dimension().location())) {
                CLIENT.computeIfAbsent(level, unused -> new ClientWires()).replace(packet.chunk, packet.wires);
            }
        });
    }

    @SubscribeEvent
    public static void sendChunk(ChunkWatchEvent.Sent event) {
        var sync = CatenaryLines.get(event.getLevel()).wires();
        long chunk = event.getPos().toLong();
        if (sync.chunks.containsKey(chunk)) {
            PacketDistributor.sendToPlayer(event.getPlayer(), sync.payload(event.getLevel(), chunk));
        }
    }

    @SubscribeEvent
    public static void forgetChunk(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide) {
            var client = CLIENT.get(level);
            if (client != null) client.replace(event.getChunk().getPos().toLong(), new CompoundTag());
        }
    }

    @SubscribeEvent
    public static void forgetLevel(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide) CLIENT.remove(level);
    }

    void add(UUID id, BlockPos section, CatenarySpan span) {
        var entry = new CompoundTag();
        entry.putUUID("Id", id);
        entry.putLong("Section", section.asLong());
        entry.putLong("Origin", span.origin().asLong());
        entry.put("Span", span.write());
        for (long chunk : span.chunks()) {
            chunks.computeIfAbsent(chunk, unused -> new LinkedHashMap<>()).put(id, entry);
            dirty.add(chunk);
        }
    }

    void remove(UUID id, CatenarySpan span) {
        for (long chunk : span.chunks()) {
            var wires = chunks.get(chunk);
            if (wires != null && wires.remove(id) != null) {
                if (wires.isEmpty()) chunks.remove(chunk);
                dirty.add(chunk);
            }
        }
    }

    void publish(ServerLevel level) {
        for (long chunk : dirty) {
            var pos = new ChunkPos(chunk);
            if (!level.getChunkSource().chunkMap.getPlayers(pos, false).isEmpty()) {
                PacketDistributor.sendToPlayersTrackingChunk(level, pos, payload(level, chunk));
            }
        }
        dirty.clear();
    }

    private ChunkWires payload(ServerLevel level, long chunk) {
        var wires = new ListTag();
        var data = chunks.get(chunk);
        if (data != null) wires.addAll(data.values());
        var tag = new CompoundTag();
        tag.put("Wires", wires);
        return new ChunkWires(level.dimension().location(), chunk, tag);
    }

    static Collection<CatenarySpan> visible(Level level) {
        var client = CLIENT.get(level);
        return client == null ? Set.of() : client.sections.keySet();
    }

    static CatenarySpan.Contact nearest(Level level, Vec3 base) {
        var client = CLIENT.get(level);
        return client == null ? null : client.index.nearestRun(base, client.sections::get, null);
    }

    private static final class ClientWires {
        final CatenarySpan.Index index = new CatenarySpan.Index();
        final Map<CatenarySpan, BlockPos> sections = new HashMap<>();
        final Map<Long, Set<UUID>> chunks = new HashMap<>();
        final Map<UUID, Wire> wires = new HashMap<>();

        void replace(long chunk, CompoundTag data) {
            var previous = chunks.remove(chunk);
            var found = new HashSet<UUID>();
            for (Tag item : data.getList("Wires", Tag.TAG_COMPOUND)) {
                if (!(item instanceof CompoundTag entry) || !entry.hasUUID("Id")) continue;
                var id = entry.getUUID("Id");
                if (!found.add(id)) continue;
                if (previous != null && previous.remove(id)) continue;
                var wire = wires.get(id);
                if (wire == null) {
                    var span = CatenarySpan.read(BlockPos.of(entry.getLong("Origin")), entry.getList("Span", Tag.TAG_COMPOUND));
                    if (span == null) {
                        found.remove(id);
                        continue;
                    }
                    wire = new Wire(span);
                    wires.put(id, wire);
                    sections.put(span, BlockPos.of(entry.getLong("Section")));
                    index.add(span);
                }
                wire.watchers++;
            }
            if (previous != null) {
                for (var id : previous) {
                    var wire = wires.get(id);
                    if (--wire.watchers == 0) {
                        wires.remove(id);
                        sections.remove(wire.span);
                        index.remove(wire.span);
                    }
                }
            }
            if (!found.isEmpty()) chunks.put(chunk, found);
        }
    }

    private static final class Wire {
        final CatenarySpan span;
        int watchers;

        Wire(CatenarySpan span) { this.span = span; }
    }

    private record ChunkWires(ResourceLocation dimension, long chunk, CompoundTag wires) implements CustomPacketPayload {
        static final Type<ChunkWires> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "catenary_chunk_wires"));
        static final StreamCodec<RegistryFriendlyByteBuf, ChunkWires> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, ChunkWires::dimension, ByteBufCodecs.VAR_LONG, ChunkWires::chunk,
                ByteBufCodecs.COMPOUND_TAG, ChunkWires::wires, ChunkWires::new);

        @Override public Type<ChunkWires> type() { return TYPE; }
    }
}
