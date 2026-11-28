package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = PowerDock.ID)
final class CatenaryLines extends SavedData {
    private static final Factory<CatenaryLines> FACTORY = new Factory<>(CatenaryLines::new, CatenaryLines::read);
    private static final Map<ServerLevel, CatenaryLines> ACTIVE = new WeakHashMap<>();
    private final Map<UUID, Link> spans = new LinkedHashMap<>();
    private final Map<BlockPos, Set<Link>> byHanger = new HashMap<>();
    private final Set<EndpointPair> legacyCuts = new HashSet<>();
    private final Map<UUID, Set<Link>> byTrackGraph = new HashMap<>();
    private final Map<CatenaryRoute.RailEdge, Set<Link>> byRail = new HashMap<>();
    private final Map<TrackNodeLocation, Set<Link>> unbound = new HashMap<>();
    private final Set<Link> revalidate = new LinkedHashSet<>();
    private final Map<UUID, TrackGraph> editedGraphs = new HashMap<>();
    private final CatenarySpan.Index index = new CatenarySpan.Index();
    private final CatenaryWireSync wires = new CatenaryWireSync();
    private final Map<BlockPos, CatenaryNetwork> networks = new HashMap<>();
    private final Map<CatenarySpan, CatenaryNetwork> sections = new IdentityHashMap<>();
    private final Map<BlockPos, Pickup> taps = new HashMap<>();
    private final Set<BlockPos> pendingLayouts = new LinkedHashSet<>();
    private final Set<CatenaryNetwork> pendingNetworks = new LinkedHashSet<>();
    private long layoutRevision;

    static CatenaryLines get(ServerLevel level) {
        return ACTIVE.computeIfAbsent(level, world -> world.getDataStorage().computeIfAbsent(FACTORY, "powerdock_catenary"));
    }

    static void trackChanged(TrackGraph graph) {
        for (var lines : ACTIVE.values()) {
            lines.revalidate.addAll(lines.byTrackGraph.getOrDefault(graph.id, Set.of()));
            if (!lines.unbound.isEmpty()) lines.editedGraphs.put(graph.id, graph);
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) ACTIVE.remove(level);
    }

    Set<Link> attached(BlockPos pos) {
        return Collections.unmodifiableSet(byHanger.getOrDefault(pos, Set.of()));
    }

    boolean overlapsOtherLine(ServerLevel level, BlockPos from, BlockPos to, int fromHeight, int toHeight,
                              CatenaryRoute.Route route) {
        var path = route.trackPath();
        var height = new HeightProfile(path, fromHeight, toHeight);
        Set<Link> possible = new HashSet<>();
        for (var interval : path) possible.addAll(byRail.getOrDefault(interval.edge(), Set.of()));
        possible.removeIf(link -> {
            var old = link.route(level);
            return old == null || !overlaps(height, new HeightProfile(old.trackPath(), link.fromHeight, link.toHeight));
        });
        if (possible.isEmpty()) return false;
        var connected = new ArrayDeque<BlockPos>();
        var seen = new HashSet<BlockPos>();
        connected.add(from);
        connected.add(to);
        while (!connected.isEmpty() && !possible.isEmpty()) {
            var pos = connected.remove();
            if (!seen.add(pos)) continue;
            for (var link : attached(pos)) {
                if (link.route(level) == null) continue;
                possible.remove(link);
                connected.add(link.opposite(pos));
            }
        }
        return !possible.isEmpty();
    }

    private static boolean overlaps(HeightProfile first, HeightProfile second) {
        double firstOffset = 0;
        for (var a : first.path) {
            double secondOffset = 0;
            for (var b : second.path) {
                long start = Math.max(a.low(), b.low());
                long end = Math.min(a.high(), b.high());
                if (a.edge().equals(b.edge()) && end > start) {
                    double startDifference = first.heightAt(a, firstOffset, start) - second.heightAt(b, secondOffset, start);
                    double endDifference = first.heightAt(a, firstOffset, end) - second.heightAt(b, secondOffset, end);
                    double midDifference = first.heightAt(a, firstOffset, start + (end - start) / 2)
                            - second.heightAt(b, secondOffset, start + (end - start) / 2);
                    double min = Math.min(midDifference, Math.min(startDifference, endDifference));
                    double max = Math.max(midDifference, Math.max(startDifference, endDifference));
                    if (min < 0.125 && max > -0.125) return true;
                }
                secondOffset += b.length();
            }
            firstOffset += a.length();
        }
        return false;
    }

    private record HeightProfile(List<CatenaryRoute.Interval> path, int start, int end, double length) {
        HeightProfile(List<CatenaryRoute.Interval> path, int start, int end) {
            this(path, start, end, path.stream().mapToDouble(CatenaryRoute.Interval::length).sum());
        }

        double heightAt(CatenaryRoute.Interval interval, double offset, long position) {
            double progress = (offset + Math.abs(interval.from() - position) * CatenaryRoute.EPSILON) / length;
            return start + (end - start) * progress * progress * (3 - 2 * progress);
        }
    }

    @Nullable Link incoming(BlockPos pos) {
        return attached(pos).stream().filter(link -> link.to.equals(pos)).findFirst().orElse(null);
    }

    List<Link> outgoing(BlockPos pos) {
        return attached(pos).stream().filter(link -> link.from.equals(pos)).toList();
    }

    Link addSpan(CatenaryLayout.Span span) {
        return store(span.id(), span.from(), span.to(), span.fromHeight(), span.toHeight(), span.route(), false);
    }

    void importLegacy(BlockPos from, BlockPos to, int fromHeight, int toHeight, CatenaryRoute.Route route) {
        if (!legacyCut(from, to)) store(UUID.randomUUID(), from, to, fromHeight, toHeight, route, true);
    }

    boolean legacyCut(BlockPos from, BlockPos to) {
        return legacyCuts.contains(EndpointPair.of(from, to));
    }

    void rememberLegacyCut(BlockPos from, BlockPos to) {
        if (legacyCuts.add(EndpointPair.of(from, to))) setDirty();
    }

    private Link store(UUID id, BlockPos from, BlockPos to, int fromHeight, int toHeight,
                       CatenaryRoute.Route route, boolean legacy) {
        var span = CatenarySpan.create(to, route, fromHeight, toHeight);
        var link = new Link(id, from, to, fromHeight, toHeight, route.save(), span, legacy);
        link.route = route;
        link.checkedRevision = CatenaryTrackIndex.revision();
        link.active = true;
        add(link);
        setDirty();
        return link;
    }

    void unlink(BlockPos pos) {
        List.copyOf(attached(pos)).forEach(this::cut);
    }

    boolean cut(Link link) {
        if (spans.get(link.id) != link) return false;
        remove(link);
        if (link.legacy) rememberLegacyCut(link.from, link.to);
        setDirty();
        return true;
    }

    void resize(ServerLevel level, BlockPos pos, int height) {
        for (var link : List.copyOf(attached(pos))) {
            var route = link.route(level);
            if (route == null) {
                if (link.from.equals(pos)) link.fromHeight = height;
                else link.toHeight = height;
                setDirty();
                continue;
            }
            remove(link);
            store(link.id, link.from, link.to, link.from.equals(pos) ? height : link.fromHeight,
                    link.to.equals(pos) ? height : link.toHeight, route, link.legacy);
        }
    }

    boolean contact(BlockPos pickup, Vec3 base, @Nullable LoconauticsCompatibility.OccupiedRails rails) {
        var old = taps.get(pickup);
        var contact = index.nearestRun(base, sections::get, old == null ? null : old.span, span -> {
            if (rails == null) return false;
            var keys = sections.get(span).wires().get(span).rails();
            return rails.leading() != null && keys.contains(rails.leading())
                    || rails.trailing() != null && keys.contains(rails.trailing());
        });
        if (contact == null) {
            release(pickup);
            return false;
        }
        var network = sections.get(contact.span());
        if (old != null && old.network != network) {
            release(pickup);
        }
        if (old == null || old.span != contact.span()) taps.put(pickup, new Pickup(network, contact.span()));
        if (network.move(pickup, contact)) pendingNetworks.add(network);
        return true;
    }

    void release(BlockPos pickup) {
        var contact = taps.remove(pickup);
        if (contact == null) return;
        contact.network.release(pickup);
        pendingNetworks.add(contact.network);
    }

    void updatePost(BlockPos pos) {
        var section = networks.get(pos);
        if (section != null && section.updatePost(pos)) pendingNetworks.add(section);
    }

    CatenaryWireSync wires() { return wires; }
    long layoutRevision() { return layoutRevision; }

    List<CatenarySpan> preview(ServerLevel level, CatenaryLayout.Span link) {
        var first = connectedRoutes(level, link.from());
        var second = connectedRoutes(level, link.to());
        var existing = new LinkedHashMap<UUID, CatenaryLayout.Span>();
        for (var span : first) existing.put(span.id(), span);
        for (var span : second) existing.put(span.id(), span);
        var oldHeads = new HashMap<List<CatenaryLayout.Fragment>, List<CatenarySpan.Head>>();
        rememberGeometry(first, oldHeads);
        if (!first.containsAll(second)) rememberGeometry(second, oldHeads);
        var combined = new ArrayList<>(existing.values());
        combined.add(link);
        var changed = new ArrayList<CatenarySpan>();
        CatenaryNetwork.orient(CatenaryLayout.build(combined)).forEach((run, span) -> {
            var previous = oldHeads.get(run.key());
            if (run.spans().contains(link.id()) || previous == null || !sameGeometry(previous, span.points)) changed.add(span);
        });
        return changed;
    }

    private static void rememberGeometry(List<CatenaryLayout.Span> spans,
                                         Map<List<CatenaryLayout.Fragment>, List<CatenarySpan.Head>> old) {
        CatenaryNetwork.orient(CatenaryLayout.build(spans)).forEach((run, span) -> {
            old.put(run.key(), span.points);
            old.put(run.key().reversed(), span.points.reversed());
        });
    }

    private static boolean sameGeometry(List<CatenarySpan.Head> previous, List<CatenarySpan.Head> updated) {
        if (previous.size() != updated.size()) return false;
        for (int i = 0; i < previous.size(); i++) {
            var a = previous.get(i);
            var b = updated.get(i);
            if (a.center().distanceToSqr(b.center()) > 1e-8 || a.across().distanceToSqr(b.across()) > 1e-8) return false;
        }
        return true;
    }

    List<Vec3> wireEnds(BlockPos pos) {
        var network = networks.get(pos);
        return network == null ? List.of() : network.wireEnds(pos);
    }

    @Nullable CatenarySpan.Head hangerHead(BlockPos pos) {
        for (var link : attached(pos)) {
            if (link.active) {
                var head = link.from.equals(pos) ? link.span.points.getFirst() : link.span.points.getLast();
                return head.alignToHanger();
            }
        }
        return null;
    }

    void detachPost(BlockPos pos) {
        var section = networks.get(pos);
        if (section != null && section.detachPost(pos)) pendingNetworks.add(section);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void updateCircuits(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) get(level).update(level);
    }

    private void update(ServerLevel level) {
        for (var graph : editedGraphs.values()) {
            for (var node : graph.getNodes()) revalidate.addAll(unbound.getOrDefault(node, Set.of()));
        }
        editedGraphs.clear();
        for (var link : revalidate) {
            var route = link.route(level);
            boolean active = route != null;
            if (active != link.active) {
                link.active = active;
                layoutChanged(link);
            }
            if (active) {
                if (link.geometryFromHeight != link.fromHeight || link.geometryToHeight != link.toHeight) {
                    link.geometryFromHeight = link.fromHeight;
                    link.geometryToHeight = link.toHeight;
                    link.span = CatenarySpan.create(link.to, route, link.fromHeight, link.toHeight);
                    layoutChanged(link);
                    setDirty();
                }
                indexRoute(link, route);
                unbindIndexRemove(link);
            } else if (link.firstNode != null) {
                unbound.computeIfAbsent(link.firstNode.in(level), unused -> new LinkedHashSet<>()).add(link);
            }
        }
        revalidate.clear();
        if (!pendingLayouts.isEmpty()) rebuild(level);
        pendingNetworks.forEach(CatenaryNetwork::update);
        pendingNetworks.clear();
        wires.publish(level);
    }

    private void rebuild(ServerLevel level) {
        Set<CatenaryNetwork> stale = new HashSet<>();
        for (var pos : pendingLayouts) {
            var network = networks.get(pos);
            if (network != null) stale.add(network);
        }
        stale.forEach(this::discard);
        var remaining = new ArrayDeque<>(pendingLayouts);
        pendingLayouts.clear();
        while (!remaining.isEmpty()) {
            var pos = remaining.remove();
            if (networks.containsKey(pos)) continue;
            var routes = connectedRoutes(level, pos);
            if (routes.isEmpty()) continue;
            var network = new CatenaryNetwork(level, CatenaryLayout.build(routes));
            for (var hanger : network.hangers()) {
                networks.put(hanger, network);
                network.updatePost(hanger);
            }
            network.wires().forEach((span, wire) -> {
                sections.put(span, network);
                index.add(span);
                wires.add(wire.id(), network.anchor(), span);
            });
            pendingNetworks.add(network);
        }
    }

    private void discard(CatenaryNetwork network) {
        for (var pos : network.hangers()) {
            networks.remove(pos, network);
            pendingLayouts.add(pos);
        }
        network.wires().forEach((span, wire) -> {
            sections.remove(span);
            index.remove(span);
            wires.remove(wire.id(), span);
        });
        for (var pickup : network.pickupPositions()) taps.remove(pickup);
        pendingNetworks.remove(network);
        network.close();
    }

    private List<CatenaryLayout.Span> connectedRoutes(ServerLevel level, BlockPos seed) {
        var remaining = new ArrayDeque<BlockPos>();
        var seen = new HashSet<Link>();
        var routes = new ArrayList<CatenaryLayout.Span>();
        remaining.add(seed);
        while (!remaining.isEmpty()) {
            var pos = remaining.remove();
            for (var link : attached(pos)) {
                if (!seen.add(link)) continue;
                var route = link.route(level);
                if (route == null) continue;
                routes.add(new CatenaryLayout.Span(link.id, link.from, link.to, link.fromHeight, link.toHeight, route));
                remaining.add(link.opposite(pos));
            }
        }
        return routes;
    }

    private void layoutChanged(Link link) {
        layoutRevision++;
        pendingLayouts.add(link.from);
        pendingLayouts.add(link.to);
    }

    @Nullable
    static CatenaryHangerBlockEntity loadedHanger(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        return chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) instanceof CatenaryHangerBlockEntity hanger
                && !hanger.isRemoved() && !hanger.isChunkUnloaded() ? hanger : null;
    }

    private void add(Link link) {
        spans.put(link.id, link);
        byHanger.computeIfAbsent(link.from, unused -> new LinkedHashSet<>()).add(link);
        byHanger.computeIfAbsent(link.to, unused -> new LinkedHashSet<>()).add(link);
        if (link.trackPath.hasUUID("Graph")) addGraphIndex(link, link.trackPath.getUUID("Graph"));
        if (link.route != null) indexRoute(link, link.route);
        else revalidate.add(link);
        layoutChanged(link);
    }

    private void remove(Link link) {
        spans.remove(link.id, link);
        removeAt(link.from, link);
        removeAt(link.to, link);
        for (var graph : link.graphs) removeIndexed(byTrackGraph, graph, link);
        for (var rail : link.rails) removeIndexed(byRail, rail, link);
        unbindIndexRemove(link);
        revalidate.remove(link);
        layoutChanged(link);
    }

    private void removeAt(BlockPos pos, Link link) {
        removeIndexed(byHanger, pos, link);
    }

    private void unbindIndexRemove(Link link) {
        if (link.firstNode != null && link.firstNode.dimension != null) removeIndexed(unbound, link.firstNode, link);
    }

    private static <K> void removeIndexed(Map<K, Set<Link>> index, K key, Link link) {
        var links = index.get(key);
        if (links != null && links.remove(link) && links.isEmpty()) index.remove(key);
    }

    private void indexRoute(Link link, CatenaryRoute.Route route) {
        addGraphIndex(link, route.graph().id);
        for (var interval : route.trackPath()) {
            if (link.rails.add(interval.edge())) {
                byRail.computeIfAbsent(interval.edge(), unused -> new LinkedHashSet<>()).add(link);
            }
        }
    }

    private void addGraphIndex(Link link, UUID graph) {
        if (link.graphs.add(graph)) byTrackGraph.computeIfAbsent(graph, unused -> new LinkedHashSet<>()).add(link);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ListTag();
        for (var link : spans.values()) {
            var entry = new CompoundTag();
            entry.putUUID("Id", link.id);
            entry.putLong("From", link.from.asLong());
            entry.putLong("To", link.to.asLong());
            entry.putInt("FromHeight", link.fromHeight);
            entry.putInt("ToHeight", link.toHeight);
            if (link.geometryFromHeight != link.fromHeight) entry.putInt("GeometryFromHeight", link.geometryFromHeight);
            if (link.geometryToHeight != link.toHeight) entry.putInt("GeometryToHeight", link.geometryToHeight);
            if (link.suspended) entry.putBoolean("Suspended", true);
            if (link.legacy) entry.putBoolean("Legacy", true);
            entry.put("Route", link.trackPath.copy());
            entry.put("Span", link.span.write());
            entries.add(entry);
        }
        tag.put("Links", entries);
        var cuts = new ListTag();
        for (var cut : legacyCuts) {
            var entry = new CompoundTag();
            entry.putLong("From", cut.first.asLong());
            entry.putLong("To", cut.second.asLong());
            cuts.add(entry);
        }
        tag.put("LegacyCuts", cuts);
        tag.putInt("GraphVersion", 1);
        return tag;
    }

    private static CatenaryLines read(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new CatenaryLines();
        for (var value : tag.getList("LegacyCuts", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            result.legacyCuts.add(EndpointPair.of(BlockPos.of(entry.getLong("From")), BlockPos.of(entry.getLong("To"))));
        }
        var entries = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            if (!entry.contains("From", Tag.TAG_LONG) || !entry.contains("To", Tag.TAG_LONG)) continue;
            var from = BlockPos.of(entry.getLong("From"));
            var to = BlockPos.of(entry.getLong("To"));
            var id = entry.hasUUID("Id") ? entry.getUUID("Id") : UUID.randomUUID();
            if (from.equals(to) || result.spans.containsKey(id)) continue;
            var span = CatenarySpan.read(to, entry.getList("Span", Tag.TAG_COMPOUND));
            var route = entry.getCompound("Route");
            if (span != null && !route.isEmpty()) {
                var link = new Link(id, from, to, Math.clamp(entry.getInt("FromHeight"), 4, 12),
                        Math.clamp(entry.getInt("ToHeight"), 4, 12), route, span, !entry.hasUUID("Id") || entry.getBoolean("Legacy"));
                if (entry.contains("GeometryFromHeight")) link.geometryFromHeight = Math.clamp(entry.getInt("GeometryFromHeight"), 4, 12);
                if (entry.contains("GeometryToHeight")) link.geometryToHeight = Math.clamp(entry.getInt("GeometryToHeight"), 4, 12);
                link.suspended = entry.getBoolean("Suspended");
                result.add(link);
            }
        }
        if (!tag.contains("GraphVersion")) result.setDirty();
        return result;
    }

    static final class Link {
        final UUID id;
        final BlockPos from, to;
        int fromHeight, toHeight;
        private int geometryFromHeight, geometryToHeight;
        CatenarySpan span;
        final boolean legacy;
        private final CompoundTag trackPath;
        private final Set<UUID> graphs = new HashSet<>();
        private final Set<CatenaryRoute.RailEdge> rails = new HashSet<>();
        @Nullable private final TrackNodeLocation firstNode;
        private long checkedRevision = Long.MIN_VALUE;
        private boolean suspended;
        private boolean active;
        @Nullable private CatenaryRoute.Route route;

        Link(UUID id, BlockPos from, BlockPos to, int fromHeight, int toHeight, CompoundTag trackPath,
             CatenarySpan span, boolean legacy) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.fromHeight = fromHeight;
            this.toHeight = toHeight;
            geometryFromHeight = fromHeight;
            geometryToHeight = toHeight;
            this.trackPath = trackPath;
            this.span = span;
            this.legacy = legacy;
            var edges = trackPath.getList("Edges", Tag.TAG_COMPOUND);
            this.firstNode = edges.isEmpty() ? null : TrackNodeLocation.read(edges.getCompound(0).getCompound("Start"), null);
        }

        BlockPos opposite(BlockPos pos) { return from.equals(pos) ? to : from; }

        @Nullable
        CatenaryRoute.Route route(ServerLevel level) {
            if (suspended) return null;
            long revision = CatenaryTrackIndex.revision();
            if (checkedRevision == revision) return route;
            checkedRevision = revision;
            if (route != null && route.valid()) return route;
            var restored = CatenaryRoute.restore(level, trackPath);
            route = restored != null && matches(CatenarySpan.create(to, restored, geometryFromHeight, geometryToHeight)) ? restored : null;
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

    private record EndpointPair(BlockPos first, BlockPos second) {
        static EndpointPair of(BlockPos a, BlockPos b) {
            return a.asLong() <= b.asLong() ? new EndpointPair(a, b) : new EndpointPair(b, a);
        }
    }

    private record Pickup(CatenaryNetwork network, CatenarySpan span) {}
}
