package org.cha0scollective.powerdock;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongConsumer;

final class SpatialBuckets<T> {
    private static final int FINE_SHIFT = 4;
    private static final int COARSE_SHIFT = 8;
    private static final int MAX_CELLS = 64;

    private final Long2ObjectOpenHashMap<List<Entry<T>>> fine = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<List<Entry<T>>> coarse = new Long2ObjectOpenHashMap<>();
    private final List<Entry<T>> oversized = new ArrayList<>();
    private long nextOrder;

    Entry<T> add(AABB bounds, T value) {
        int shift = cells(bounds, FINE_SHIFT) <= MAX_CELLS ? FINE_SHIFT
                : cells(bounds, COARSE_SHIFT) <= MAX_CELLS ? COARSE_SHIFT : 0;
        var entry = new Entry<>(bounds, value, shift, nextOrder++);
        if (shift == 0) oversized.add(entry);
        else {
            var buckets = shift == FINE_SHIFT ? fine : coarse;
            visit(bounds, shift, key -> buckets.computeIfAbsent(key, unused -> new ArrayList<>()).add(entry));
        }
        return entry;
    }

    void remove(Entry<T> entry) {
        if (entry.shift == 0) oversized.remove(entry);
        else {
            var buckets = entry.shift == FINE_SHIFT ? fine : coarse;
            visit(entry.bounds, entry.shift, key -> {
                var entries = buckets.get(key);
                if (entries.remove(entry) && entries.isEmpty()) buckets.remove(key);
            });
        }
    }

    void clear() {
        fine.clear();
        coarse.clear();
        oversized.clear();
        nextOrder = 0;
    }

    List<T> find(AABB bounds) {
        var hits = new ArrayList<Entry<T>>();
        find(fine, bounds, FINE_SHIFT, hits);
        find(coarse, bounds, COARSE_SHIFT, hits);
        for (var entry : oversized) if (overlap(entry.bounds, bounds)) hits.add(entry);
        if (hits.isEmpty()) return List.of();
        hits.sort(Comparator.comparingLong(Entry::order));
        var values = new ArrayList<T>(hits.size());
        for (var entry : hits) values.add(entry.value);
        return values;
    }

    private static <T> void find(Long2ObjectOpenHashMap<List<Entry<T>>> buckets, AABB bounds, int shift,
                                 List<Entry<T>> hits) {
        if (buckets.isEmpty()) return;
        visit(bounds, shift, key -> {
            var entries = buckets.get(key);
            if (entries == null) return;
            for (var entry : entries) {
                var item = entry.bounds;
                // An entry only contributes from the first shared cell.
                long firstShared = SectionPos.asLong(cell(Math.max(item.minX, bounds.minX), shift),
                        cell(Math.max(item.minY, bounds.minY), shift), cell(Math.max(item.minZ, bounds.minZ), shift));
                if (key == firstShared && overlap(item, bounds)) hits.add(entry);
            }
        });
    }

    private static boolean overlap(AABB first, AABB second) {
        return first.minX <= second.maxX && first.maxX >= second.minX
                && first.minY <= second.maxY && first.maxY >= second.minY
                && first.minZ <= second.maxZ && first.maxZ >= second.minZ;
    }

    private static long cells(AABB bounds, int shift) {
        return (cell(bounds.maxX, shift) - cell(bounds.minX, shift) + 1L)
                * (cell(bounds.maxY, shift) - cell(bounds.minY, shift) + 1L)
                * (cell(bounds.maxZ, shift) - cell(bounds.minZ, shift) + 1L);
    }

    private static void visit(AABB bounds, int shift, LongConsumer handle) {
        int maxX = cell(bounds.maxX, shift);
        int maxY = cell(bounds.maxY, shift);
        int maxZ = cell(bounds.maxZ, shift);
        for (int x = cell(bounds.minX, shift); x <= maxX; x++)
            for (int y = cell(bounds.minY, shift); y <= maxY; y++)
                for (int z = cell(bounds.minZ, shift); z <= maxZ; z++)
                    handle.accept(SectionPos.asLong(x, y, z));
    }

    private static int cell(double coordinate, int shift) { return Mth.floor(coordinate) >> shift; }

    record Entry<T>(AABB bounds, T value, int shift, long order) {}
}
