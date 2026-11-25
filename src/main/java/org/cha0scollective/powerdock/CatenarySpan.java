package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@EventBusSubscriber(modid = PowerDock.ID)
final class CatenarySpan {
    static final double HALF_SPACING = 0.5;
    static final double SIDE_TRAVEL = 1.25;
    static final double MIN_RISE = 0.75;
    static final double MAX_RISE = 5.5;
    private static final Map<Level, ClientSpans> CLIENT_SPANS = new IdentityHashMap<>();
    private final BlockPos end;
    final List<Head> points;
    final AABB renderBounds;
    private final List<Segment> segments = new ArrayList<>();

    private CatenarySpan(BlockPos end, List<Head> points) {
        this.end = end;
        this.points = points;
        var bounds = new AABB(end);
        for (var point : points) {
            var center = point.center();
            bounds = bounds.minmax(new AABB(center.x, center.y, center.z, center.x, center.y, center.z));
        }
        renderBounds = bounds.inflate(8, 16, 8);
        for (int i = 1; i < points.size(); i++) segments.add(new Segment(end, points.get(i - 1), points.get(i)));
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide) CLIENT_SPANS.remove(level);
    }

    static CatenarySpan create(BlockPos end, CatenaryRoute.Route path, int startHeight, int endHeight) {
        var route = path.points();
        var points = new ArrayList<Head>(route.size());
        double total = 0;
        for (int i = 1; i < route.size(); i++) total += route.get(i).distanceTo(route.get(i - 1));
        double walked = 0;
        for (int i = 0; i < route.size(); i++) {
            if (i > 0) walked += route.get(i).distanceTo(route.get(i - 1));
            double t = walked / total;
            double height = startHeight + (endHeight - startHeight) * t * t * (3 - 2 * t);
            Vec3 tangent;
            if (i == 0) tangent = path.direction(true);
            else if (i == route.size() - 1) tangent = path.direction(false);
            else tangent = route.get(i + 1).subtract(route.get(i - 1));
            points.add(Head.along(route.get(i).add(0, height, 0), tangent));
        }
        return new CatenarySpan(end, List.copyOf(points));
    }

    void registerClient(Level level, BlockPos feeder) {
        var client = CLIENT_SPANS.computeIfAbsent(level, unused -> new ClientSpans(new Index(), new HashMap<>()));
        client.index.add(this);
        client.feeders.put(end, feeder);
    }

    void unregisterClient(Level level) {
        var client = CLIENT_SPANS.get(level);
        if (client == null) return;
        client.index.remove(this);
        client.feeders.remove(end);
        if (client.feeders.isEmpty()) CLIENT_SPANS.remove(level);
    }

    @Nullable
    static Contact nearestClient(Level level, Vec3 base) {
        var client = CLIENT_SPANS.get(level);
        return client == null ? null : client.index.nearest(base, client.feeders::get);
    }

    private record ClientSpans(Index index, Map<BlockPos, BlockPos> feeders) {}

    static final class Index {
        private final Map<Long, Set<Segment>> cells = new HashMap<>();

        void add(CatenarySpan span) {
            for (var segment : span.segments) {
                segment.forCells(cell -> cells.computeIfAbsent(cell, unused -> new HashSet<>()).add(segment));
            }
        }

        void remove(CatenarySpan span) {
            for (var segment : span.segments) {
                segment.forCells(cell -> {
                    var contents = cells.get(cell);
                    if (contents != null && contents.remove(segment) && contents.isEmpty()) cells.remove(cell);
                });
            }
        }

        @Nullable
        Contact nearest(Vec3 base, Function<BlockPos, BlockPos> feederAt) {
            var entries = cells.get(new ChunkPos(BlockPos.containing(base)).toLong());
            if (entries == null) return null;
            Contact best = null;
            boolean tiedSection = false;
            for (var segment : entries) {
                var sample = segment.sample(base);
                if (sample == null || best != null && sample.score > best.score + 1e-6) continue;
                var feeder = feederAt.apply(segment.end);
                if (feeder == null) continue;
                if (best != null && Math.abs(sample.score - best.score) < 1e-6) {
                    if (!feeder.equals(best.feeder)) tiedSection = true;
                    continue;
                }
                best = new Contact(feeder, sample.head, sample.score);
                tiedSection = false;
            }
            return tiedSection ? null : best;
        }
    }

    ListTag write() {
        var data = new ListTag();
        var origin = Vec3.atLowerCornerOf(end);
        for (var point : points) {
            var local = point.center.subtract(origin);
            var entry = new CompoundTag();
            entry.putDouble("X", local.x);
            entry.putDouble("Y", local.y);
            entry.putDouble("Z", local.z);
            entry.putDouble("AcrossX", point.across.x);
            entry.putDouble("AcrossZ", point.across.z);
            data.add(entry);
        }
        return data;
    }

    @Nullable
    static CatenarySpan read(BlockPos end, ListTag entries) {
        if (entries.size() < 2 || entries.size() > 512) return null;
        var origin = Vec3.atLowerCornerOf(end);
        var points = new ArrayList<Head>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            var pos = origin.add(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            var across = new Vec3(entry.getDouble("AcrossX"), 0, entry.getDouble("AcrossZ"));
            if (!Double.isFinite(pos.lengthSqr()) || !Double.isFinite(across.lengthSqr())
                    || pos.distanceToSqr(origin) > 96 * 96 || across.lengthSqr() > 1) return null;
            points.add(new Head(pos, across));
        }
        return new CatenarySpan(end, List.copyOf(points));
    }

    record Head(Vec3 center, Vec3 across) {
        static Head along(Vec3 center, Vec3 tangent) {
            var across = new Vec3(-tangent.z, 0, tangent.x).normalize().scale(HALF_SPACING);
            return new Head(center, across);
        }

        Vec3 supply() { return center.add(across); }
        Vec3 returnWire() { return center.subtract(across); }
    }

    record Contact(BlockPos feeder, Head head, double score) {}
    private record Sample(Head head, double score) {}

    private record Segment(BlockPos end, Head first, Head second) {
        void forCells(java.util.function.LongConsumer handle) {
            var a = first.center;
            var b = second.center;
            int minX = ((int) Math.floor(Math.min(a.x, b.x) - SIDE_TRAVEL)) >> 4;
            int maxX = ((int) Math.floor(Math.max(a.x, b.x) + SIDE_TRAVEL)) >> 4;
            int minZ = ((int) Math.floor(Math.min(a.z, b.z) - SIDE_TRAVEL)) >> 4;
            int maxZ = ((int) Math.floor(Math.max(a.z, b.z) + SIDE_TRAVEL)) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) handle.accept(ChunkPos.asLong(x, z));
        }

        @Nullable
        Sample sample(Vec3 base) {
            var a = first.center;
            var b = second.center;
            double x = b.x - a.x, z = b.z - a.z;
            double length = x * x + z * z;
            if (length < 1e-8) return null;
            double t = ((base.x - a.x) * x + (base.z - a.z) * z) / length;
            if (t < -1e-6 || t > 1 + 1e-6) return null;
            var center = a.lerp(b, Math.clamp(t, 0, 1));
            double rise = center.y - base.y;
            if (rise < MIN_RISE || rise > MAX_RISE) return null;
            double dx = base.x - center.x, dz = base.z - center.z;
            double lateral = dx * dx + dz * dz;
            if (lateral > SIDE_TRAVEL * SIDE_TRAVEL) return null;
            return new Sample(new Head(center, first.across.lerp(second.across, t)), lateral + rise * rise * 0.01);
        }
    }
}
