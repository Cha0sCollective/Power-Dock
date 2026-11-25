package org.cha0scollective.powerdock;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class CatenaryRoute {
    static final int MAX_LENGTH = 64;
    private static final int MAX_EDGES = 128;
    private static final double EPSILON = 1e-5;
    private static final double SNAP_DISTANCE = 0.75;

    private CatenaryRoute() {}

    @Nullable
    static BlockPos trackBelow(ServerLevel level, BlockPos hanger) {
        for (int depth = 1; depth <= 16; depth++) {
            var pos = hanger.below(depth);
            if (level.getBlockState(pos).getBlock() instanceof ITrackBlock) return pos;
        }
        return null;
    }

    @Nullable
    static Route betweenHangers(ServerLevel level, BlockPos from, BlockPos to) {
        if (horizontalDistanceSqr(Vec3.atCenterOf(from), Vec3.atCenterOf(to))
                > (MAX_LENGTH + 2 * SNAP_DISTANCE) * (MAX_LENGTH + 2 * SNAP_DISTANCE)) return null;
        var targets = locationsBelow(level, to);
        Route chosen = null;
        for (var start : locationsBelow(level, from)) {
            var search = new Search(start.graph, targets);
            search.walk(start.edge, start.distance, 0, new ArrayList<>());
            if (search.exhausted || search.results.size() > 1) return null;
            if (search.results.isEmpty()) continue;
            var route = search.results.getFirst();
            if (chosen != null && !chosen.points.equals(route.points)) return null;
            chosen = route;
        }
        return chosen;
    }

    private static List<Location> locationsBelow(ServerLevel level, BlockPos hanger) {
        var rail = trackBelow(level, hanger);
        if (rail != null) return locations(level, rail);

        var column = Vec3.atBottomCenterOf(hanger);
        CurveAnchor best = null;
        boolean ambiguous = false;
        for (var candidate : CatenaryTrackIndex.curvesBelow(level, column)) {
            var curve = candidate.connection();
            double t = closestParameter(curve, column);
            var point = curve.getPosition(t);
            double distance = horizontalDistanceSqr(column, point);
            double drop = column.y - point.y;
            if (distance > SNAP_DISTANCE * SNAP_DISTANCE || drop < 0.5 || drop > 16) continue;
            if (best != null && Math.abs(distance - best.distance) < EPSILON) {
                if (Math.abs(point.y - best.height) < 0.5) ambiguous = true;
                if (point.y <= best.height + 0.5) continue;
            } else if (best != null && distance > best.distance) continue;
            best = new CurveAnchor(candidate.graph(), curve, t, point.y, distance);
            ambiguous = false;
        }
        return best == null || ambiguous ? List.of() : best.locations(level);
    }

    @Nullable
    static CatenarySpan.Head placementHead(ServerLevel level, BlockPos hanger) {
        CatenarySpan.Head result = null;
        for (var location : locationsBelow(level, hanger)) {
            var edge = location.edge;
            var center = edge.getPosition(location.graph, location.distance / edge.getLength());
            var head = CatenarySpan.Head.along(center, edge.getDirectionAt(location.distance));
            if (head.across().lengthSqr() < EPSILON) continue;
            if (result != null && (result.center().distanceToSqr(center) > EPSILON
                    || Math.abs(result.across().dot(head.across())) < CatenarySpan.HALF_SPACING * CatenarySpan.HALF_SPACING - EPSILON)) {
                return null;
            }
            result = head;
        }
        return result;
    }

    private static double closestParameter(BezierConnection curve, Vec3 column) {
        int steps = Math.clamp(curve.getSegmentCount(), 8, 512);
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int step = 0; step <= steps; step++) {
            double distance = horizontalDistanceSqr(column, curve.getPosition((double) step / steps));
            if (distance < best) {
                nearest = step;
                best = distance;
            }
        }
        double lo = (double) Math.max(0, nearest - 1) / steps;
        double hi = (double) Math.min(steps, nearest + 1) / steps;
        for (int i = 0; i < 18; i++) {
            double third = (hi - lo) / 3;
            if (horizontalDistanceSqr(column, curve.getPosition(lo + third))
                    < horizontalDistanceSqr(column, curve.getPosition(hi - third))) hi -= third;
            else lo += third;
        }
        return (lo + hi) * 0.5;
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double x = a.x - b.x, z = a.z - b.z;
        return x * x + z * z;
    }

    private record CurveAnchor(TrackGraph graph, BezierConnection curve, double t, double height, double distance) {
        List<Location> locations(ServerLevel level) {
            var first = graph.locateNode(node(level, true));
            var second = graph.locateNode(node(level, false));
            if (first == null || second == null) return List.of();
            var forward = graph.getConnection(Couple.create(first, second));
            var backward = graph.getConnection(Couple.create(second, first));
            if (forward == null || backward == null || forward.getTurn() != curve || !backward.isTurn()) return List.of();
            return List.of(new Location(graph, forward, t * forward.getLength()),
                    new Location(graph, backward, (1 - t) * backward.getLength()));
        }

        private TrackNodeLocation node(ServerLevel level, boolean first) {
            var start = curve.starts.get(first);
            var node = new TrackNodeLocation(start).in(level);
            node.yOffsetPixels = curve.yOffsetAt(start);
            return node;
        }
    }

    private static List<Location> locations(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return List.of();
        var locations = new ArrayList<Location>();
        for (var axis : track.getTrackAxes(level, pos, state)) {
            for (var direction : Direction.AxisDirection.values()) {
                var location = CatenaryTrackIndex.locationAt(level, pos, direction, axis);
                if (location == null || location.edge == null) continue;
                var graph = location.graph;
                var first = graph.locateNode(location.edge.getFirst());
                var second = graph.locateNode(location.edge.getSecond());
                if (first == null || second == null) continue;
                var edge = graph.getConnection(Couple.create(first, second));
                if (edge == null || edge.isInterDimensional() || edge.getLength() < EPSILON) continue;
                double distance = Math.clamp(location.position, 0, edge.getLength());
                var result = new Location(graph, edge, distance);
                if (!locations.contains(result)) locations.add(result);
            }
        }
        return locations;
    }

    record Route(TrackGraph graph, List<Part> parts, List<Vec3> points) {
        Vec3 direction(boolean start) {
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(start ? i : parts.size() - 1 - i);
                if (part.to - part.from > EPSILON) return part.edge.getDirectionAt(start ? part.from : part.to);
            }
            return Vec3.ZERO;
        }

        boolean valid() {
            if (Create.RAILWAYS.trackNetworks.get(graph.id) != graph) return false;
            for (var part : parts) {
                var connections = graph.getConnectionsFrom(part.edge.node1);
                if (connections == null || connections.get(part.edge.node2) != part.edge) return false;
            }
            return true;
        }
    }
    private record Location(TrackGraph graph, TrackEdge edge, double distance) {}
    private record Part(TrackEdge edge, double from, double to) {}

    private static final class Search {
        final TrackGraph graph;
        final List<Location> targets;
        final List<Route> results = new ArrayList<>(2);
        final Set<TrackEdge> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int examined;
        boolean exhausted;

        Search(TrackGraph graph, List<Location> targets) {
            this.graph = graph;
            this.targets = targets;
        }

        void walk(TrackEdge edge, double begin, double length, List<Part> parts) {
            if (results.size() > 1 || exhausted || !visited.add(edge)) return;
            if (++examined > MAX_EDGES) {
                exhausted = true;
                return;
            }
            for (var target : targets) {
                if (target.graph != graph || target.edge != edge || target.distance + EPSILON < begin) continue;
                double total = length + target.distance - begin;
                if (total < 0.5 || total > MAX_LENGTH + EPSILON) continue;
                parts.add(new Part(edge, begin, target.distance));
                var points = sample(parts);
                var route = new Route(graph, List.copyOf(parts), points);
                parts.removeLast();
                if (points.size() > 1 && results.stream().noneMatch(result -> result.points.equals(points))) {
                    results.add(route);
                }
            }
            double fullLength = edge.getLength();
            double nextLength = length + fullLength - begin;
            if (nextLength <= MAX_LENGTH && results.size() < 2) {
                parts.add(new Part(edge, begin, fullLength));
                var exits = graph.getConnectionsFrom(edge.node2);
                if (exits != null) {
                    for (TrackNode destination : exits.keySet()) {
                        var next = exits.get(destination);
                        if (destination == edge.node1 || next.isInterDimensional() || !edge.canTravelTo(next)) continue;
                        walk(next, 0, nextLength, parts);
                    }
                }
                parts.removeLast();
            }
            visited.remove(edge);
        }

        List<Vec3> sample(List<Part> parts) {
            var points = new ArrayList<Vec3>();
            for (var part : parts) {
                int steps = Math.max(1, (int) Math.ceil((part.to - part.from) * 2));
                double edgeLength = part.edge.getLength();
                for (int i = 0; i <= steps; i++) {
                    var pos = part.edge.getPosition(graph, (part.from + (part.to - part.from) * i / steps) / edgeLength);
                    if (points.isEmpty() || points.getLast().distanceToSqr(pos) > EPSILON * EPSILON) points.add(pos);
                }
            }
            return List.copyOf(points);
        }
    }
}
