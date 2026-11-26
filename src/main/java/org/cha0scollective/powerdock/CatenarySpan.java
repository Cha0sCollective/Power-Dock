package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

final class CatenarySpan {
    static final double HALF_SPACING = 0.5;
    static final double SIDE_TRAVEL = 1.25;
    static final double MIN_RISE = 0.75;
    static final double MAX_RISE = 5.5;
    private final BlockPos end;
    final List<Head> points;
    final AABB renderBounds;
    final boolean crossoverAtStart;
    final boolean crossoverAtEnd;
    private final List<Segment> segments = new ArrayList<>();
    private final double[] distance;
    private final double[] supplyDistance;
    private final double[] returnDistance;
    private final Set<Long> chunks = new HashSet<>();

    private CatenarySpan(BlockPos end, List<Head> points) {
        this(end, points, false, false);
    }

    private CatenarySpan(BlockPos end, List<Head> points, boolean crossoverAtStart, boolean crossoverAtEnd) {
        this.end = end;
        this.points = points;
        this.crossoverAtStart = crossoverAtStart;
        this.crossoverAtEnd = crossoverAtEnd;
        distance = new double[points.size()];
        supplyDistance = new double[points.size()];
        returnDistance = new double[points.size()];
        var start = points.getFirst().center();
        var bounds = new AABB(start.x, start.y, start.z, start.x, start.y, start.z);
        for (var point : points) {
            var center = point.center();
            bounds = bounds.minmax(new AABB(center.x, center.y, center.z, center.x, center.y, center.z));
        }
        renderBounds = bounds.inflate(HALF_SPACING + 0.125, 0.5, HALF_SPACING + 0.125);
        for (int i = 1; i < points.size(); i++) {
            var first = points.get(i - 1);
            var last = points.get(i);
            distance[i] = distance[i - 1] + first.center.distanceTo(last.center);
            supplyDistance[i] = supplyDistance[i - 1] + first.supply().distanceTo(last.supply());
            returnDistance[i] = returnDistance[i - 1] + first.returnWire().distanceTo(last.returnWire());
            var segment = new Segment(this, i - 1, first, last);
            segments.add(segment);
            segment.forCells(chunks::add);
        }
    }

    BlockPos origin() { return end; }
    Set<Long> chunks() { return chunks; }

    static CatenarySpan create(BlockPos end, CatenaryRoute.Route path, int startHeight, int endHeight) {
        return create(end, path.points(), path.direction(true), path.direction(false), startHeight, endHeight);
    }

    static CatenarySpan create(BlockPos end, List<Vec3> route, Vec3 startDirection, Vec3 endDirection,
                               int startHeight, int endHeight) {
        var points = new ArrayList<Head>(route.size());
        double total = 0;
        for (int i = 1; i < route.size(); i++) total += route.get(i).distanceTo(route.get(i - 1));
        double walked = 0;
        for (int i = 0; i < route.size(); i++) {
            if (i > 0) walked += route.get(i).distanceTo(route.get(i - 1));
            double t = walked / total;
            double height = startHeight + (endHeight - startHeight) * t * t * (3 - 2 * t);
            Vec3 tangent;
            if (i == 0) tangent = startDirection;
            else if (i == route.size() - 1) tangent = endDirection;
            else tangent = route.get(i + 1).subtract(route.get(i - 1));
            points.add(Head.along(route.get(i).add(0, height, 0), tangent));
        }
        return new CatenarySpan(end, List.copyOf(points));
    }

    CatenarySpan flipped() {
        return new CatenarySpan(end, points.stream().map(head -> new Head(head.center, head.across.reverse())).toList());
    }

    CatenarySpan join(Vec3 first, Vec3 last) {
        var joined = new ArrayList<>(points);
        joined.set(0, join(points.getFirst(), first));
        joined.set(points.size() - 1, join(points.getLast(), last));
        return new CatenarySpan(end, List.copyOf(joined), points.getFirst().across.dot(first) < 0,
                points.getLast().across.dot(last) < 0);
    }

    private static Head join(Head head, Vec3 contactAcross) {
        return new Head(head.center, head.across.dot(contactAcross) < 0 ? contactAcross.reverse() : contactAcross);
    }

    Distance length() {
        int last = points.size() - 1;
        return new Distance(distance[last], supplyDistance[last], returnDistance[last]);
    }

    private Distance at(int segment, double t) {
        return new Distance(distance[segment] + t * (distance[segment + 1] - distance[segment]),
                supplyDistance[segment] + t * (supplyDistance[segment + 1] - supplyDistance[segment]),
                returnDistance[segment] + t * (returnDistance[segment + 1] - returnDistance[segment]));
    }

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
        <K> Contact nearestRun(Vec3 base, Function<CatenarySpan, K> sectionAt, @Nullable CatenarySpan previous) {
            return nearestRun(base, sectionAt, previous, span -> false);
        }

        @Nullable
        <K> Contact nearestRun(Vec3 base, Function<CatenarySpan, K> sectionAt, @Nullable CatenarySpan previous,
                               Predicate<CatenarySpan> occupied) {
            var entries = cells.get(new ChunkPos(BlockPos.containing(base)).toLong());
            if (entries == null) return null;
            Contact best = null;
            K section = null;
            boolean tiedSection = false;
            boolean bestOccupied = false;
            for (var segment : entries) {
                var sample = segment.sample(base);
                if (sample == null || best != null && sample.score > best.score + 1e-6) continue;
                var candidateSection = sectionAt.apply(segment.span);
                if (candidateSection == null) continue;
                boolean onTrack = occupied.test(segment.span);
                if (best != null && Math.abs(sample.score - best.score) < 1e-6) {
                    if (onTrack && !bestOccupied) {
                        section = candidateSection;
                        best = new Contact(segment.span, sample.head, sample.at, sample.score);
                        bestOccupied = true;
                        tiedSection = false;
                    } else if (onTrack == bestOccupied) {
                        if (!candidateSection.equals(section)) tiedSection = true;
                        else if (segment.span == previous) best = new Contact(segment.span, sample.head, sample.at, sample.score);
                    }
                    continue;
                }
                best = new Contact(segment.span, sample.head, sample.at, sample.score);
                section = candidateSection;
                bestOccupied = onTrack;
                tiedSection = false;
            }
            return tiedSection ? null : best;
        }
    }

    ListTag write() {
        var data = new ListTag();
        var origin = Vec3.atLowerCornerOf(end);
        for (var point : points) data.add(point.write(origin));
        if (crossoverAtStart) data.getCompound(0).putBoolean("Crossover", true);
        if (crossoverAtEnd) data.getCompound(data.size() - 1).putBoolean("Crossover", true);
        return data;
    }

    @Nullable
    static CatenarySpan read(BlockPos end, ListTag entries) {
        if (entries.size() < 2 || entries.size() > 512) return null;
        var origin = Vec3.atLowerCornerOf(end);
        var points = new ArrayList<Head>();
        for (int i = 0; i < entries.size(); i++) {
            var head = Head.read(origin, entries.getCompound(i));
            if (head == null) return null;
            points.add(head);
        }
        return new CatenarySpan(end, List.copyOf(points), entries.getCompound(0).getBoolean("Crossover"),
                entries.getCompound(entries.size() - 1).getBoolean("Crossover"));
    }

    record Head(Vec3 center, Vec3 across) {
        static Head along(Vec3 center, Vec3 tangent) {
            var across = new Vec3(-tangent.z, 0, tangent.x).normalize().scale(HALF_SPACING);
            return new Head(center, across);
        }

        Vec3 supply() { return center.add(across); }
        Vec3 returnWire() { return center.subtract(across); }

        CompoundTag write(Vec3 origin) {
            var local = center.subtract(origin);
            var entry = new CompoundTag();
            entry.putDouble("X", local.x);
            entry.putDouble("Y", local.y);
            entry.putDouble("Z", local.z);
            entry.putDouble("AcrossX", across.x);
            entry.putDouble("AcrossZ", across.z);
            return entry;
        }

        @Nullable static Head read(Vec3 origin, CompoundTag entry) {
            var pos = origin.add(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            var across = new Vec3(entry.getDouble("AcrossX"), 0, entry.getDouble("AcrossZ"));
            if (!Double.isFinite(pos.lengthSqr()) || !Double.isFinite(across.lengthSqr())
                    || pos.distanceToSqr(origin) > 96 * 96 || across.lengthSqr() > 1) return null;
            return new Head(pos, across);
        }
    }

    record Distance(double center, double supply, double returnWire) {
        static final Distance ZERO = new Distance(0, 0, 0);

        Distance plus(Distance other) {
            return new Distance(center + other.center, supply + other.supply, returnWire + other.returnWire);
        }

        Distance minus(Distance other) {
            return new Distance(center - other.center, supply - other.supply, returnWire - other.returnWire);
        }
    }

    record Contact(CatenarySpan span, Head head, Distance at, double score) {}
    private record Sample(Head head, Distance at, double score) {}

    private record Segment(CatenarySpan span, int number, Head first, Head second) {
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
            t = Math.clamp(t, 0, 1);
            var center = a.lerp(b, t);
            double rise = center.y - base.y;
            if (rise < MIN_RISE || rise > MAX_RISE) return null;
            double dx = base.x - center.x, dz = base.z - center.z;
            double lateral = dx * dx + dz * dz;
            if (lateral > SIDE_TRAVEL * SIDE_TRAVEL) return null;
            return new Sample(new Head(center, first.across.lerp(second.across, t)), span.at(number, t),
                    lateral + rise * rise * 0.01);
        }
    }
}
