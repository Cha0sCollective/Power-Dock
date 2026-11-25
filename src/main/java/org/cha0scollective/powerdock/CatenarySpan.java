package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
import java.util.function.Predicate;

@EventBusSubscriber(modid = PowerDock.ID)
final class CatenarySpan {
    static final double HALF_SPACING = 0.5;
    static final double SIDE_TRAVEL = 1.25;
    static final double MIN_RISE = 0.75;
    static final double MAX_RISE = 5.5;
    private static final Map<Level, Map<Long, Set<Segment>>> BY_LEVEL = new IdentityHashMap<>();
    private final CatenaryHangerBlockEntity owner;
    final List<Head> points;
    final AABB renderBounds;
    private final List<Segment> segments = new ArrayList<>();

    private CatenarySpan(CatenaryHangerBlockEntity owner, List<Head> points) {
        this.owner = owner;
        this.points = points;
        var bounds = new AABB(owner.getBlockPos());
        for (var point : points) {
            var center = point.center();
            bounds = bounds.minmax(new AABB(center.x, center.y, center.z, center.x, center.y, center.z));
        }
        renderBounds = bounds.inflate(8, 16, 8);
        for (int i = 1; i < points.size(); i++) segments.add(new Segment(owner, points.get(i - 1), points.get(i)));
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) BY_LEVEL.remove(level);
    }

    static CatenarySpan create(CatenaryHangerBlockEntity owner, CatenaryRoute.Route path, int startHeight, int endHeight) {
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
        return new CatenarySpan(owner, List.copyOf(points));
    }

    void register(Level level) {
        var cells = BY_LEVEL.computeIfAbsent(level, unused -> new HashMap<>());
        for (var segment : segments) {
            segment.forCells(cell -> cells.computeIfAbsent(cell, unused -> new HashSet<>()).add(segment));
        }
    }

    void unregister(Level level) {
        var cells = BY_LEVEL.get(level);
        if (cells == null) return;
        for (var segment : segments) {
            segment.forCells(cell -> {
                var contents = cells.get(cell);
                if (contents != null && contents.remove(segment) && contents.isEmpty()) cells.remove(cell);
            });
        }
        if (cells.isEmpty()) BY_LEVEL.remove(level);
    }

    @Nullable
    static Contact nearest(Level level, Vec3 base, Predicate<Contact> available) {
        var cells = BY_LEVEL.get(level);
        if (cells == null) return null;
        var entries = cells.get(new ChunkPos(BlockPos.containing(base)).toLong());
        if (entries == null) return null;
        Contact best = null;
        boolean tiedSection = false;
        for (var segment : entries) {
            var contact = segment.contact(base);
            if (contact == null || (best != null && contact.score > best.score + 1e-6) || !available.test(contact)) continue;
            if (best != null && Math.abs(contact.score - best.score) < 1e-6) {
                if (contact.feeder != best.feeder) tiedSection = true;
                continue;
            }
            best = contact;
            tiedSection = false;
        }
        return tiedSection ? null : best;
    }

    ListTag write() {
        var data = new ListTag();
        var origin = Vec3.atLowerCornerOf(owner.getBlockPos());
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
    static CatenarySpan read(CatenaryHangerBlockEntity owner, CompoundTag data) {
        var entries = data.getList("Span", Tag.TAG_COMPOUND);
        if (entries.size() < 2 || entries.size() > 512) return null;
        var origin = Vec3.atLowerCornerOf(owner.getBlockPos());
        var points = new ArrayList<Head>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            var pos = origin.add(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            var across = new Vec3(entry.getDouble("AcrossX"), 0, entry.getDouble("AcrossZ"));
            points.add(new Head(pos, across));
        }
        return new CatenarySpan(owner, List.copyOf(points));
    }

    record Head(Vec3 center, Vec3 across) {
        static Head along(Vec3 center, Vec3 tangent) {
            var across = new Vec3(-tangent.z, 0, tangent.x).normalize().scale(HALF_SPACING);
            return new Head(center, across);
        }

        Vec3 supply() { return center.add(across); }
        Vec3 returnWire() { return center.subtract(across); }
    }

    record Contact(CatenaryHangerBlockEntity feeder, Head head, double score) {}

    private record Segment(CatenaryHangerBlockEntity owner, Head first, Head second) {
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
        Contact contact(Vec3 base) {
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
            var feeder = owner.feeder();
            return feeder == null ? null
                    : new Contact(feeder, new Head(center, first.across.lerp(second.across, t)), lateral + rise * rise * 0.01);
        }
    }
}
