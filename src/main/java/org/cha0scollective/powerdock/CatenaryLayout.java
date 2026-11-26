package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Converts the selected routes of one connected line into physical pairs of wires. */
final class CatenaryLayout {
    private CatenaryLayout() {}

    record Span(UUID id, BlockPos from, BlockPos to, int fromHeight, int toHeight, CatenaryRoute.Route route) {}

    record Port(CatenaryRoute.RailEdge edge, long distance) {}

    record Fragment(CatenaryRoute.RailEdge edge, long low, long high) {
        Port first() { return new Port(edge, low); }
        Port last() { return new Port(edge, high); }
        double length() { return (high - low) * CatenaryRoute.EPSILON; }
    }

    record Joint(Port key, Vec3 rail, Map<BlockPos, Integer> hangers, int height) {}

    record Run(List<Fragment> key, Joint from, Joint to, List<Vec3> rail, Vec3 departure,
               Vec3 arrival, Set<UUID> spans) {}

    static List<Run> build(List<Span> spans) {
        var builder = new Builder();
        var ordered = spans.stream().sorted(Comparator.comparing(Span::id)).toList();
        for (var span : ordered) builder.addCuts(span.route.trackPath());
        for (var span : ordered) builder.addSpan(span);
        return builder.runs();
    }

    private record Geometry(TrackGraph graph, CatenaryRoute.Part part, CatenaryRoute.Interval interval) {
        Vec3 at(long distance) {
            double along = along(distance);
            return part.edge().getPosition(graph, along / part.edge().getLength());
        }

        Vec3 tangent(long distance, boolean forward) {
            var direction = part.edge().getDirectionAt(along(distance));
            return forward == (interval.from() < interval.to()) ? direction : direction.reverse();
        }

        private double along(long distance) {
            double length = part.edge().getLength();
            double along = distance * CatenaryRoute.EPSILON;
            if (interval.from() > interval.to()) along = length - along;
            return Math.clamp(along, 0, length);
        }
    }

    private static final class Piece {
        final Fragment key;
        final Geometry geometry;
        final Set<UUID> spans = new HashSet<>();

        Piece(Fragment key, Geometry geometry) {
            this.key = key;
            this.geometry = geometry;
        }
    }

    private static final class Builder {
        private final Map<CatenaryRoute.RailEdge, TreeSet<Long>> cuts = new HashMap<>();
        private final Map<Fragment, Piece> pieces = new LinkedHashMap<>();
        private final Map<Port, Port> parents = new HashMap<>();
        private final Map<Port, Vec3> positions = new HashMap<>();
        private final Map<BlockPos, Port> anchors = new HashMap<>();
        private final Map<BlockPos, Integer> heights = new HashMap<>();

        void addCuts(List<CatenaryRoute.Interval> path) {
            for (var interval : path) {
                var boundaries = cuts.computeIfAbsent(interval.edge(), edge -> new TreeSet<>());
                boundaries.add(interval.low());
                boundaries.add(interval.high());
            }
        }

        void addSpan(Span span) {
            Port start = null;
            Port previous = null;
            for (var part : span.route.parts()) {
                if (part.to() - part.from() <= CatenaryRoute.EPSILON) continue;
                var interval = part.interval();
                if (interval.from() == interval.to()) continue;
                var geometry = new Geometry(span.route.graph(), part, interval);
                var boundaries = cuts.get(interval.edge()).subSet(interval.low(), true, interval.high(), true);
                var sequence = interval.from() < interval.to() ? boundaries : boundaries.descendingSet();
                Long last = null;
                for (long point : sequence) {
                    if (last != null) {
                        var fragment = new Fragment(interval.edge(), Math.min(last, point), Math.max(last, point));
                        pieces.computeIfAbsent(fragment, key -> new Piece(key, geometry)).spans.add(span.id);
                    } else {
                        var first = new Port(interval.edge(), point);
                        positions.computeIfAbsent(first, port -> geometry.at(port.distance));
                        if (previous == null) start = first;
                        else join(previous, first);
                    }
                    last = point;
                }
                if (last != null) {
                    previous = new Port(interval.edge(), last);
                    positions.computeIfAbsent(previous, port -> geometry.at(port.distance));
                }
            }
            if (start != null && previous != null) {
                anchor(span.from, start, span.fromHeight);
                anchor(span.to, previous, span.toHeight);
            }
        }

        private void anchor(BlockPos hanger, Port port, int height) {
            var existing = anchors.putIfAbsent(hanger, port);
            if (existing != null) join(existing, port);
            heights.put(hanger, height);
        }

        private Port root(Port port) {
            var representative = port;
            while (parents.containsKey(representative)) representative = parents.get(representative);
            while (!port.equals(representative)) port = parents.put(port, representative);
            return representative;
        }

        private void join(Port a, Port b) {
            var first = root(a);
            var second = root(b);
            if (!first.equals(second)) parents.put(second, first);
        }

        List<Run> runs() {
            Map<Port, List<Piece>> adjacent = new LinkedHashMap<>();
            for (var piece : pieces.values()) {
                adjacent.computeIfAbsent(root(piece.key.first()), unused -> new ArrayList<>()).add(piece);
                adjacent.computeIfAbsent(root(piece.key.last()), unused -> new ArrayList<>()).add(piece);
            }
            Map<Port, Map<BlockPos, Integer>> posts = new HashMap<>();
            anchors.forEach((pos, port) -> posts.computeIfAbsent(root(port), unused -> new HashMap<>()).put(pos, heights.get(pos)));
            var nearest = closestHangers(adjacent, posts);
            Map<Port, Joint> joints = new LinkedHashMap<>();
            adjacent.forEach((port, connections) -> {
                var hangers = posts.getOrDefault(port, Map.of());
                if (connections.size() != 2 || !hangers.isEmpty()) {
                    var height = nearest.get(port);
                    joints.put(port, new Joint(port, positions.get(port), Map.copyOf(hangers),
                            height == null ? 7 : heights.get(height.hanger)));
                }
            });
            var visited = new HashSet<Fragment>();
            var result = new ArrayList<Run>();
            for (var from : joints.values()) {
                for (var piece : adjacent.get(from.key)) {
                    if (!visited.contains(piece.key)) result.add(follow(from, piece, adjacent, joints, visited));
                }
            }
            return result;
        }

        private Run follow(Joint from, Piece piece, Map<Port, List<Piece>> adjacent,
                           Map<Port, Joint> joints, Set<Fragment> visited) {
            var path = new ArrayList<Fragment>();
            var rail = new ArrayList<Vec3>();
            var owners = new HashSet<UUID>();
            var port = from.key;
            Vec3 departure = null;
            rail.add(from.rail);
            while (true) {
                path.add(piece.key);
                owners.addAll(piece.spans);
                visited.add(piece.key);
                boolean forward = root(piece.key.first()).equals(port);
                long start = forward ? piece.key.low : piece.key.high;
                long end = forward ? piece.key.high : piece.key.low;
                if (departure == null) departure = piece.geometry.tangent(start, forward);
                int count = Math.max(1, (int) Math.ceil(piece.key.length() * 2));
                for (int i = 1; i <= count; i++) {
                    var point = piece.geometry.at(start + Math.round((end - start) * (i / (double) count)));
                    if (point.distanceToSqr(rail.getLast()) > 1e-10) rail.add(point);
                }
                port = root(forward ? piece.key.last() : piece.key.first());
                var to = joints.get(port);
                if (to != null) {
                    rail.set(rail.size() - 1, to.rail);
                    return new Run(List.copyOf(path), from, to, List.copyOf(rail), departure,
                            piece.geometry.tangent(end, forward), Set.copyOf(owners));
                }
                var next = adjacent.get(port);
                piece = next.getFirst() == piece ? next.getLast() : next.getFirst();
            }
        }

        private Map<Port, Nearest> closestHangers(Map<Port, List<Piece>> adjacent, Map<Port, Map<BlockPos, Integer>> posts) {
            var closest = new HashMap<Port, Nearest>();
            var pending = new PriorityQueue<>(Nearest.ORDER);
            posts.forEach((port, hangers) -> hangers.keySet().forEach(hanger -> pending.add(new Nearest(port, hanger, 0))));
            while (!pending.isEmpty()) {
                var next = pending.remove();
                if (closest.putIfAbsent(next.port, next) != null) continue;
                for (var piece : adjacent.get(next.port)) {
                    var first = root(piece.key.first());
                    var last = root(piece.key.last());
                    var neighbour = first.equals(next.port) ? last : first;
                    if (!closest.containsKey(neighbour)) {
                        pending.add(new Nearest(neighbour, next.hanger, next.distance + piece.key.length()));
                    }
                }
            }
            return closest;
        }
    }

    private record Nearest(Port port, BlockPos hanger, double distance) {
        private static final Comparator<Nearest> ORDER = Comparator.comparingDouble(Nearest::distance)
                .thenComparingLong(nearest -> nearest.hanger.asLong());
    }
}
