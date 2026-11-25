package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class CatenaryLines extends SavedData {
    static final int MAX_HANGERS = 32;
    private static final Factory<CatenaryLines> FACTORY = new Factory<>(CatenaryLines::new, CatenaryLines::read);
    private final Map<BlockPos, Link> incoming = new HashMap<>();
    private final Map<BlockPos, Link> outgoing = new HashMap<>();
    private final CatenarySpan.Index index = new CatenarySpan.Index();
    private final Map<BlockPos, Optional<BlockPos>> roots = new HashMap<>();
    private long routeRevision = Long.MIN_VALUE;

    static CatenaryLines get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "powerdock_catenary");
    }

    @Nullable Link incoming(BlockPos pos) { return incoming.get(pos); }
    @Nullable Link outgoing(BlockPos pos) { return outgoing.get(pos); }

    void put(BlockPos from, BlockPos to, int fromHeight, int toHeight, CatenaryRoute.Route route) {
        var span = CatenarySpan.create(to, route, fromHeight, toHeight);
        var link = new Link(from, to, fromHeight, toHeight, route.save(), span);
        link.route = route;
        link.checkedRevision = CatenaryTrackIndex.revision();
        var previous = incoming.get(to);
        if (previous != null) remove(previous);
        var next = outgoing.get(from);
        if (next != null) remove(next);
        add(link);
        roots.clear();
        setDirty();
    }

    void unlink(BlockPos pos) {
        var before = incoming.get(pos);
        var after = outgoing.get(pos);
        if (before != null) remove(before);
        if (after != null) remove(after);
        if (before != null || after != null) {
            roots.clear();
            setDirty();
        }
    }

    void suspend(Link link) {
        if (!link.suspended) {
            link.suspended = true;
            roots.clear();
            setDirty();
        }
    }

    void resize(ServerLevel level, BlockPos pos, int height) {
        var before = incoming.get(pos);
        var after = outgoing.get(pos);
        if (before != null && before.route(level) != null) {
            put(before.from, pos, before.fromHeight, height, before.route);
        }
        if (after != null && after.route(level) != null) {
            put(pos, after.to, height, after.toHeight, after.route);
        }
    }

    @Nullable
    CatenarySpan.Contact nearest(ServerLevel level, Vec3 base) {
        return index.nearest(base, end -> {
            var root = root(level, end);
            var feeder = root == null ? null : loadedHanger(level, root);
            return feeder != null && feeder.available() && feeder.isFeeder() ? root : null;
        });
    }

    @Nullable
    BlockPos root(ServerLevel level, BlockPos end) {
        long revision = CatenaryTrackIndex.revision();
        if (revision != routeRevision) {
            roots.clear();
            routeRevision = revision;
        }
        return roots.computeIfAbsent(end, pos -> Optional.ofNullable(findRoot(level, pos))).orElse(null);
    }

    @Nullable
    private BlockPos findRoot(ServerLevel level, BlockPos end) {
        var current = end;
        for (int depth = 0; depth < MAX_HANGERS; depth++) {
            var link = incoming.get(current);
            if (link == null) return current;
            if (link.route(level) == null) return null;
            current = link.from;
        }
        return null;
    }

    @Nullable
    Upstream loadedUpstream(ServerLevel level, BlockPos end) {
        var current = end;
        for (int hops = 1; hops < MAX_HANGERS; hops++) {
            var link = incoming.get(current);
            if (link == null || link.route(level) == null) return null;
            current = link.from;
            var hanger = loadedHanger(level, current);
            if (hanger != null && hanger.available()) return new Upstream(current, hops);
        }
        return null;
    }

    @Nullable
    static CatenaryHangerBlockEntity loadedHanger(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        return chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) instanceof CatenaryHangerBlockEntity hanger
                && !hanger.isRemoved() && !hanger.isChunkUnloaded() ? hanger : null;
    }

    private void add(Link link) {
        incoming.put(link.to, link);
        outgoing.put(link.from, link);
        index.add(link.span);
    }

    private void remove(Link link) {
        incoming.remove(link.to, link);
        outgoing.remove(link.from, link);
        index.remove(link.span);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ListTag();
        for (var link : incoming.values()) {
            var entry = new CompoundTag();
            entry.putLong("From", link.from.asLong());
            entry.putLong("To", link.to.asLong());
            entry.putInt("FromHeight", link.fromHeight);
            entry.putInt("ToHeight", link.toHeight);
            if (link.suspended) entry.putBoolean("Suspended", true);
            entry.put("Route", link.trackPath.copy());
            entry.put("Span", link.span.write());
            entries.add(entry);
        }
        tag.put("Links", entries);
        return tag;
    }

    private static CatenaryLines read(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new CatenaryLines();
        var entries = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            if (!entry.contains("From", Tag.TAG_LONG) || !entry.contains("To", Tag.TAG_LONG)) continue;
            var from = BlockPos.of(entry.getLong("From"));
            var to = BlockPos.of(entry.getLong("To"));
            if (from.equals(to) || result.incoming.containsKey(to) || result.outgoing.containsKey(from)) continue;
            var span = CatenarySpan.read(to, entry.getList("Span", Tag.TAG_COMPOUND));
            var route = entry.getCompound("Route");
            if (span != null && !route.isEmpty()) {
                var link = new Link(from, to, Math.clamp(entry.getInt("FromHeight"), 4, 12),
                        Math.clamp(entry.getInt("ToHeight"), 4, 12), route, span);
                link.suspended = entry.getBoolean("Suspended");
                result.add(link);
            }
        }
        return result;
    }

    record Upstream(BlockPos pos, int hops) {}

    static final class Link {
        final BlockPos from, to;
        final int fromHeight, toHeight;
        final CatenarySpan span;
        private final CompoundTag trackPath;
        private long checkedRevision = Long.MIN_VALUE;
        private boolean suspended;
        @Nullable private CatenaryRoute.Route route;

        Link(BlockPos from, BlockPos to, int fromHeight, int toHeight, CompoundTag trackPath, CatenarySpan span) {
            this.from = from;
            this.to = to;
            this.fromHeight = fromHeight;
            this.toHeight = toHeight;
            this.trackPath = trackPath;
            this.span = span;
        }

        @Nullable
        CatenaryRoute.Route route(ServerLevel level) {
            if (suspended) return null;
            long revision = CatenaryTrackIndex.revision();
            if (checkedRevision == revision) return route;
            checkedRevision = revision;
            if (route != null && route.valid()) return route;
            var restored = CatenaryRoute.restore(level, trackPath);
            route = restored != null && matches(CatenarySpan.create(to, restored, fromHeight, toHeight)) ? restored : null;
            return route;
        }

        private boolean matches(CatenarySpan candidate) {
            if (span.points.size() != candidate.points.size()) return false;
            for (int i = 0; i < span.points.size(); i++) {
                var a = span.points.get(i);
                var b = candidate.points.get(i);
                if (a.center().distanceToSqr(b.center()) > 1e-8 || a.across().distanceToSqr(b.across()) > 1e-8) {
                    return false;
                }
            }
            return true;
        }
    }
}
