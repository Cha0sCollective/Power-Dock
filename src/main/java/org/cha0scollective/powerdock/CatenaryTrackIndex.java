package org.cha0scollective.powerdock;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = PowerDock.ID)
public final class CatenaryTrackIndex {
    private static final Map<ServerLevel, CatenaryTrackIndex> LEVELS = new IdentityHashMap<>();
    private static final ThreadLocal<CatenaryTrackIndex> STRAIGHT_LOOKUP = new ThreadLocal<>();
    private static long revision;

    private final ServerLevel level;
    private final SpatialBuckets<Curve> curves = new SpatialBuckets<>();
    private final Map<TrackNodeLocation, List<TrackGraph>> nodes = new HashMap<>();
    private final Map<UUID, IndexedGraph> indexed = new HashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private Map<UUID, TrackGraph> registry;

    private CatenaryTrackIndex(ServerLevel level) {
        this.level = level;
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }

    public static void graphChanged(TrackGraph graph) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return;
        revision++;
        for (var index : LEVELS.values()) index.dirty.add(graph.id);
    }

    static long revision() { return revision; }

    @Nullable
    static TrackGraph graphAt(ServerLevel level, TrackNodeLocation location) {
        var graphs = current(level).nodes.get(location);
        return graphs == null ? null : graphs.getFirst();
    }

    static List<Curve> curvesBelow(ServerLevel level, Vec3 column) {
        return current(level).curves.find(new AABB(column.x, column.y - 16, column.z, column.x, column.y, column.z));
    }

    @Nullable
    static TrackGraphLocation locationAt(ServerLevel level, BlockPos pos, Direction.AxisDirection direction, Vec3 axis) {
        var previous = STRAIGHT_LOOKUP.get();
        STRAIGHT_LOOKUP.set(current(level));
        try {
            return TrackGraphHelper.getGraphLocationAt(level, pos, direction, axis);
        } finally {
            if (previous == null) STRAIGHT_LOOKUP.remove();
            else STRAIGHT_LOOKUP.set(previous);
        }
    }

    public static boolean usesIndexedNodes(LevelAccessor level) {
        var index = STRAIGHT_LOOKUP.get();
        return index != null && index.level == level;
    }

    @Nullable
    public static TrackGraph indexedGraph(TrackNodeLocation location) {
        var graphs = STRAIGHT_LOOKUP.get().nodes.get(location);
        return graphs == null ? null : graphs.getFirst();
    }

    private static CatenaryTrackIndex current(ServerLevel level) {
        var index = LEVELS.computeIfAbsent(level, CatenaryTrackIndex::new);
        index.refresh();
        return index;
    }

    private void refresh() {
        var live = Create.RAILWAYS.trackNetworks;
        if (registry != live) {
            registry = live;
            curves.clear();
            nodes.clear();
            indexed.clear();
            dirty.clear();
            dirty.addAll(live.keySet());
        }
        for (var id : dirty) {
            var old = indexed.remove(id);
            if (old != null) remove(old);
            var graph = live.get(id);
            if (graph != null) indexed.put(id, add(graph));
        }
        dirty.clear();
    }

    private IndexedGraph add(TrackGraph graph) {
        var graphNodes = new ArrayList<TrackNodeLocation>();
        var graphCurves = new ArrayList<SpatialBuckets.Entry<Curve>>();
        for (var location : graph.getNodes()) {
            if (!location.dimension.equals(level.dimension())) continue;
            graphNodes.add(location);
            var existing = nodes.get(location);
            if (existing == null) nodes.put(location, List.of(graph));
            else {
                var shared = new ArrayList<>(existing);
                shared.add(graph);
                nodes.put(location, shared);
            }
            var edges = graph.getConnectionsFrom(graph.locateNode(location));
            if (edges == null) continue;
            for (var edge : edges.values()) {
                var curve = edge.getTurn();
                if (curve != null && curve.isPrimary()) {
                    graphCurves.add(curves.add(curve.getBounds(), new Curve(graph, curve)));
                }
            }
        }
        return new IndexedGraph(graph, graphNodes, graphCurves);
    }

    private void remove(IndexedGraph old) {
        for (var location : old.nodes) {
            var graphs = nodes.get(location);
            if (graphs.size() == 1) nodes.remove(location);
            else graphs.remove(old.graph);
        }
        for (var curve : old.curves) curves.remove(curve);
    }

    record Curve(TrackGraph graph, BezierConnection connection) {}
    private record IndexedGraph(TrackGraph graph, List<TrackNodeLocation> nodes,
                                List<SpatialBuckets.Entry<Curve>> curves) {}
}
