package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.cha0scollective.powerdock.CatenaryContacts.Terminals;
import org.cha0scollective.powerdock.CatenarySpan.Contact;
import org.cha0scollective.powerdock.CatenarySpan.Distance;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class CatenaryNetwork {
    private static final float POST_RESISTANCE = 0.0001f;
    private final ServerLevel level;
    private final double resistancePerBlock;
    private final Map<CatenaryLayout.Joint, Terminals> junctions = new HashMap<>();
    private final Map<BlockPos, CatenaryLayout.Joint> posts = new HashMap<>();
    private final Map<CatenaryLayout.Joint, List<Wire>> adjacent = new LinkedHashMap<>();
    private final Set<BlockPos> loadedPosts = new HashSet<>();
    private final Set<BlockPos> wiredPosts = new HashSet<>();
    private final Set<BlockPos> pendingPosts = new HashSet<>();
    private final Map<BlockPos, CatenaryContacts> postConnections = new HashMap<>();
    private final Map<BlockPos, List<Vec3>> hangerEnds = new HashMap<>();
    private final Map<CatenarySpan, Wire> wires = new LinkedHashMap<>();
    private final Map<Wire, Placement> placements = new HashMap<>();
    private final Map<BlockPos, Pickup> pickups = new HashMap<>();
    private final Set<CatenaryRun> changed = new LinkedHashSet<>();
    private Map<Chain, CatenaryRun> circuits = Map.of();
    private Set<CatenaryRun> activeCircuits = Set.of();
    private boolean rebuild = true;
    private final BlockPos anchor;

    CatenaryNetwork(ServerLevel level, List<CatenaryLayout.Run> layout) {
        this.level = level;
        var copper = WireRegistry.forItem(level, BuiltInRegistries.ITEM.get(CatenaryWireUse.COPPER_WIRE));
        resistancePerBlock = copper == null ? 0.00075 : copper.resistancePerItem() * copper.itemsPerMeter();
        for (var entry : orient(layout).entrySet()) {
            var run = entry.getKey();
            var span = entry.getValue();
            addEnds(run.from(), span.points.getFirst());
            addEnds(run.to(), span.points.getLast());
            var rails = run.key().stream().map(CatenaryLayout.Fragment::edge).collect(Collectors.toUnmodifiableSet());
            var wire = new Wire(UUID.randomUUID(), span, run.from(), run.to(), rails);
            wires.put(span, wire);
            adjacent.computeIfAbsent(run.from(), unused -> new ArrayList<>()).add(wire);
            adjacent.computeIfAbsent(run.to(), unused -> new ArrayList<>()).add(wire);
        }
        anchor = posts.keySet().stream().min(Comparator.comparingLong(BlockPos::asLong)).orElse(BlockPos.ZERO);
    }

    BlockPos anchor() { return anchor; }
    Set<BlockPos> hangers() { return posts.keySet(); }
    Map<CatenarySpan, Wire> wires() { return wires; }
    List<Vec3> wireEnds(BlockPos pos) { return List.copyOf(hangerEnds.getOrDefault(pos, List.of())); }

    private void addEnds(CatenaryLayout.Joint joint, CatenarySpan.Head head) {
        for (var hanger : joint.hangers().keySet()) {
            posts.put(hanger, joint);
            var ends = hangerEnds.computeIfAbsent(hanger, unused -> new ArrayList<>());
            ends.add(head.supply());
            ends.add(head.returnWire());
        }
    }

    private Terminals terminals(CatenaryLayout.Joint joint) {
        return junctions.computeIfAbsent(joint, key -> Terminals.virtual(key.rail().add(0, key.height(), 0)));
    }

    boolean updatePost(BlockPos pos) {
        var hanger = CatenaryLines.loadedHanger(level, pos);
        if (hanger == null || !hanger.available()) return detachPost(pos);
        boolean added = loadedPosts.add(pos);
        boolean wasLive = shouldSolve();
        if (added || Math.floorMod(level.getGameTime() + pos.asLong(), 10) == 0) {
            if (hasOutsideWires(pos,hanger)) wiredPosts.add(pos);
            else wiredPosts.remove(pos);
        }
        if (added || wasLive != shouldSolve()) rebuild = true;
        if (rebuild || !circuits.isEmpty() && needsPostConnection(pos)) pendingPosts.add(pos);
        return rebuild || pendingPosts.contains(pos);
    }

    boolean detachPost(BlockPos pos) {
        boolean removed = loadedPosts.remove(pos);
        pendingPosts.remove(pos);
        wiredPosts.remove(pos);
        openPost(pos);
        if (removed) rebuild = true;
        return removed;
    }

    private boolean needsPostConnection(BlockPos pos) {
        var contacts = postConnections.get(pos);
        return contacts == null || !contacts.live();
    }

    private void openPost(BlockPos pos) {
        var contacts = postConnections.remove(pos);
        if (contacts != null) contacts.open();
    }

    private boolean hasOutsideWires(BlockPos pos,CatenaryHangerBlockEntity hanger) {
        var real = Terminals.block(pos);
        var contact = postConnections.get(pos);
        return hasOutsideWires(real.supply(), contact == null ? null : contact.supply(),hanger)
                || hasOutsideWires(real.returnWire(), contact == null ? null : contact.returnWire(),hanger)
                || hanger.support().terminals() && (hasOutsideWires(new BlockWireEndpoint(pos,2),null,hanger)
                || hasOutsideWires(new BlockWireEndpoint(pos,3),null,hanger));
    }

    private boolean hasOutsideWires(IWireEndpoint end, ElectricWire owned,CatenaryHangerBlockEntity hanger) {
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var node = end.getNode(level);
        if (node == null) return false;
        for (var wire : grid.globalGraph.getWires(node)) if (wire != owned && !hanger.internalBridge(wire) && !hanger.clipped.ownsWire(level,wire)) return true;
        var connected = grid.findConnectedWires(end);
        return connected != null && connected.stream().anyMatch(segment -> !(segment.owner instanceof ClippedLeadEntity));
    }

    private boolean shouldSolve() {
        return loadedPosts.size() + pickups.size() >= 2 && (!wiredPosts.isEmpty() || !pickups.isEmpty());
    }

    Set<BlockPos> pickupPositions() { return pickups.keySet(); }

    boolean move(BlockPos pos, Contact contact) {
        var pickup = pickups.get(pos);
        if (pickup == null) {
            boolean wasLive = shouldSolve();
            pickup = new Pickup();
            pickups.put(pos, pickup);
            if (wasLive != shouldSolve()) rebuild = true;
        } else if (pickup.contact.span() == contact.span() && pickup.contact.at().equals(contact.at())
                && pickup.circuit != null && pickup.circuit.livePickup(pos)) {
            return false;
        }
        pickup.contact = contact;
        if (!rebuild) positionPickup(pos, pickup);
        return true;
    }

    void release(BlockPos pos) {
        var removed = pickups.remove(pos);
        if (removed == null) return;
        if (removed.circuit != null) {
            removed.circuit.release(pos);
            changed.add(removed.circuit);
        }
        if (!shouldSolve() && !circuits.isEmpty()) rebuild = true;
    }

    void update() {
        if (rebuild) rebuildCircuit();
        for (var pos : pendingPosts) {
            if (circuits.isEmpty() || !needsPostConnection(pos)) continue;
            openPost(pos);
            var contact = CatenaryContacts.close(level, terminals(posts.get(pos)), Terminals.block(pos),
                    POST_RESISTANCE, POST_RESISTANCE);
            if (contact != null) postConnections.put(pos, contact);
        }
        pendingPosts.clear();
        changed.forEach(CatenaryRun::update);
        changed.clear();
    }

    private void rebuildCircuit() {
        rebuild = false;
        if (!shouldSolve()) {
            closeCircuit();
            return;
        }
        var keep = new LinkedHashSet<CatenaryLayout.Joint>();
        adjacent.forEach((joint, connections) -> {
            if (connections.size() != 2 || joint.hangers().keySet().stream().anyMatch(loadedPosts::contains)) keep.add(joint);
        });
        // A closed circuit with pickups and no loaded hangers still needs an anchor for its series resistance.
        if (keep.isEmpty() && !adjacent.isEmpty()) keep.add(adjacent.keySet().iterator().next());
        var wanted = new HashMap<Chain, CatenaryRun>();
        var visited = new HashSet<Wire>();
        placements.clear();
        for (var joint : keep) {
            for (var wire : adjacent.get(joint)) {
                if (!visited.contains(wire)) follow(joint, wire, keep, visited, wanted);
            }
        }
        circuits.forEach((chain, circuit) -> {
            if (!wanted.containsKey(chain)) {
                circuit.close();
                changed.remove(circuit);
            }
        });
        circuits = wanted;
        activeCircuits = new HashSet<>(wanted.values());
        pendingPosts.addAll(loadedPosts);
        for (var entry : pickups.entrySet()) positionPickup(entry.getKey(), entry.getValue());
        junctions.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) return false;
            entry.getValue().retire(level);
            return true;
        });
    }

    private void follow(CatenaryLayout.Joint start, Wire wire, Set<CatenaryLayout.Joint> keep,
                        Set<Wire> visited, Map<Chain, CatenaryRun> wanted) {
        var parts = new ArrayList<Along>();
        var joint = start;
        var length = Distance.ZERO;
        do {
            boolean forward = joint.equals(wire.from);
            parts.add(new Along(wire, length, forward));
            length = length.plus(wire.span.length());
            visited.add(wire);
            joint = forward ? wire.to : wire.from;
            if (keep.contains(joint)) break;
            var next = adjacent.get(joint);
            wire = next.getFirst() == wire ? next.getLast() : next.getFirst();
        } while (!visited.contains(wire));
        var chain = new Chain(start, joint, List.copyOf(parts));
        var circuit = circuits.get(chain);
        if (circuit == null) {
            circuit = new CatenaryRun(level, length, terminals(start), terminals(joint), resistancePerBlock);
            changed.add(circuit);
        }
        wanted.put(chain, circuit);
        for (var part : parts) placements.put(part.wire, new Placement(circuit, part));
    }

    private void positionPickup(BlockPos pos, Pickup pickup) {
        var placement = placements.get(wires.get(pickup.contact.span()));
        var circuit = placement == null ? null : placement.circuit;
        if (pickup.circuit != null && pickup.circuit != circuit) {
            pickup.circuit.release(pos);
            if (activeCircuits.contains(pickup.circuit)) changed.add(pickup.circuit);
        }
        pickup.circuit = circuit;
        if (placement != null) {
            var part = placement.part;
            var position = pickup.contact.at();
            if (!part.forward) position = part.wire.span.length().minus(position);
            circuit.move(pos, part.offset.plus(position), pickup.contact.head().center());
            changed.add(circuit);
        }
    }

    private void closeCircuit() {
        postConnections.values().forEach(CatenaryContacts::open);
        postConnections.clear();
        circuits.values().forEach(CatenaryRun::close);
        circuits = Map.of();
        activeCircuits = Set.of();
        junctions.values().forEach(terminals -> terminals.retire(level));
        junctions.clear();
        changed.clear();
        placements.clear();
        pickups.values().forEach(pickup -> pickup.circuit = null);
    }

    void close() {
        closeCircuit();
        pickups.clear();
    }

    record Wire(UUID id, CatenarySpan span, CatenaryLayout.Joint from, CatenaryLayout.Joint to,
                Set<CatenaryRoute.RailEdge> rails) {}

    private record Along(Wire wire, Distance offset, boolean forward) {}
    private record Placement(CatenaryRun circuit, Along part) {}
    private record Chain(CatenaryLayout.Joint start, CatenaryLayout.Joint end, List<Along> parts) {}

    private static final class Pickup {
        Contact contact;
        CatenaryRun circuit;
    }

    static Map<CatenaryLayout.Run, CatenarySpan> orient(List<CatenaryLayout.Run> layout) {
        Map<CatenaryLayout.Joint, List<End>> adjacent = new LinkedHashMap<>();
        Map<CatenaryLayout.Run, CatenarySpan> geometry = new LinkedHashMap<>();
        for (var run : layout) {
            var rail = run.rail();
            if (rail.size() < 2) continue;
            var span = CatenarySpan.create(BlockPos.containing(run.to().rail()), rail, run.departure(), run.arrival(),
                    run.from().height(), run.to().height());
            geometry.put(run, span);
            adjacent.computeIfAbsent(run.from(), unused -> new ArrayList<>()).add(new End(run, true));
            adjacent.computeIfAbsent(run.to(), unused -> new ArrayList<>()).add(new End(run, false));
        }
        var orientation = new Orientation();
        // Keep every possible conductor on its original side through virtual turnouts. Real hangers can house crossovers.
        for (boolean virtual : new boolean[]{true, false}) {
            adjacent.forEach((joint, ends) -> {
                if (joint.hangers().isEmpty() != virtual) return;
                var first = ends.getFirst();
                for (var other : ends) {
                    orientation.link(first.run, other.run, first.across(geometry).dot(other.across(geometry)) < 0);
                }
            });
        }
        geometry.replaceAll((run, span) -> orientation.find(run).flipped ? span.flipped() : span);
        Map<CatenaryLayout.Joint, Vec3> across = new HashMap<>();
        adjacent.forEach((joint, ends) -> across.put(joint, ends.getFirst().across(geometry)));
        geometry.replaceAll((run, span) -> span.join(across.get(run.from()), across.get(run.to())));
        return geometry;
    }

    private record End(CatenaryLayout.Run run, boolean start) {
        Vec3 across(Map<CatenaryLayout.Run, CatenarySpan> geometry) {
            var points = geometry.get(run).points;
            return (start ? points.getFirst() : points.getLast()).across();
        }
    }

    private static final class Orientation {
        private final Map<CatenaryLayout.Run, Root> parents = new HashMap<>();
        private final Map<CatenaryLayout.Run, Integer> weights = new HashMap<>();

        Root find(CatenaryLayout.Run run) {
            var parent = parents.get(run);
            if (parent == null) return new Root(run, false);
            var above = find(parent.run);
            var result = new Root(above.run, parent.flipped ^ above.flipped);
            parents.put(run, result);
            return result;
        }

        void link(CatenaryLayout.Run first, CatenaryLayout.Run second, boolean opposite) {
            var a = find(first);
            var b = find(second);
            if (a.run.equals(b.run)) return;
            int aSize = weights.getOrDefault(a.run, 1);
            int bSize = weights.getOrDefault(b.run, 1);
            var parent = aSize >= bSize ? a.run : b.run;
            var child = aSize >= bSize ? b.run : a.run;
            parents.put(child, new Root(parent, a.flipped ^ b.flipped ^ opposite));
            weights.put(parent, aSize + bSize);
        }
    }

    private record Root(CatenaryLayout.Run run, boolean flipped) {
    }
}
