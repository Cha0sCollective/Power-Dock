package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class FrameWiring {
    static final int FIRST_CONNECTOR = 4;
    private final Map<Integer, TubeFitting> fittings = new LinkedHashMap<>();
    private final Map<Integer, HangerFrame.Member> bodies = new LinkedHashMap<>();
    private final Map<Integer, TerminalBoundingBox> terminals = new HashMap<>();
    private HangerFrame cached;
    int rejected = -1;

    Map<Integer, TubeFitting> fittings() { return Collections.unmodifiableMap(fittings); }
    int count(boolean posts) {
        int count = posts ? 4 : 2;
        for (var index : fittings.keySet()) count = Math.max(count, index+1);
        return count;
    }
    private HangerFrame.Member body(int index, HangerFrame frame) {
        if (frame == null) return null;
        if (index == 2 || index == 3) return frame.member("feed-post-"+(index-2));
        var fitting = fittings.get(index);
        return fitting == null ? null : fitting.body(frame);
    }
    Map<Integer, HangerFrame.Member> bodies(HangerFrame frame) {
        if (cached != frame) {
            bodies.clear();
            terminals.clear();
            cached = frame;
            if (frame != null) for (int i = 2;i<count(frame.feedPlate != null);i++) {
                var part = body(i, frame);
                if (part != null) {
                    bodies.put(i, part);
                    terminals.put(i, TubeFitting.terminal(part, i));
                }
            }
        }
        return bodies;
    }
    TerminalBoundingBox terminal(HangerFrame frame, int index) {
        bodies(frame);
        return terminals.get(index);
    }
    void put(int slot, TubeFitting fitting) { fittings.put(slot, fitting); cached = null; }
    void remove(int slot) { fittings.remove(slot); cached = null; }
    int freeSlot() {
        for (int i = FIRST_CONNECTOR;i<260;i++) if (!fittings.containsKey(i)) return i;
        return -1;
    }
    void write(CompoundTag tag) {
        var list = new ListTag();
        fittings.forEach((slot, fitting)->list.add(fitting.write(slot)));
        if (!list.isEmpty()) tag.put("TubeConnectors", list);
    }
    boolean read(CompoundTag tag) {
        if (fittings.isEmpty() && !tag.contains("TubeConnectors")) return false;
        var incoming = new LinkedHashMap<Integer, TubeFitting>();
        for (var entry : tag.getList("TubeConnectors", Tag.TAG_COMPOUND)) {
            var data = (CompoundTag)entry;
            int slot = data.getInt("Slot");
            var fitting = TubeFitting.read(data);
            if (slot>=FIRST_CONNECTOR && slot<260 && fitting != null) incoming.put(slot, fitting);
        }
        if (!fittings.equals(incoming)) {
            fittings.clear(); fittings.putAll(incoming);
            bodies.clear(); terminals.clear(); cached = null;
            return true;
        }
        return false;
    }

    boolean placed(ServerLevel level, Player player, CatenaryHangerBlockEntity hanger, HangerFrame.Member part) {
        var offset = Vec3.atLowerCornerOf(hanger.getBlockPos());
        var box = TubeFitting.bounds(part).move(offset).deflate(.0001);
        var normal = part.axis();
        var side = Direction.getNearest(normal.x, normal.y, normal.z);
        for (var cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var cellBox = new AABB(cell).move(offset.scale(-1));
            if (!overlap(part, boxBody(cellBox))) continue;
            if (!level.hasChunkAt(cell) || level.isOutsideBuildHeight(cell)) return false;
            var state = level.getBlockState(cell);
            if (!cell.equals(hanger.getBlockPos()) && !cell.equals(hanger.headPosition())
                    && state.getCollisionShape(level, cell).toAabbs().stream().anyMatch(b -> overlap(part, boxBody(b.move(cell).move(offset.scale(-1)))))) return false;
            if (player != null && (!level.mayInteract(player, cell) || !player.mayBuild()
                    || EventHooks.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, cell), side))) return false;
        }
        return true;
    }

    boolean validate(CatenaryHangerBlockEntity hanger, HangerFrame next, Vec3 headShift, Player player) {
        rejected = -1;
        var level = (ServerLevel)hanger.getLevel();
        var origin = Vec3.atLowerCornerOf(hanger.getBlockPos());
        var electric = hanger.getElectricBehaviour();
        var before = bodies(hanger.frame());
        var newEnds = new HashMap<Integer, Vec3>();
        var fitted = new LinkedHashMap<Integer, HangerFrame.Member>();
        for (int i = 0;i<2;i++) newEnds.put(i, hanger.terminal(hanger.getBlockState(), i).getOrigin().add(headShift).add(origin));
        for (int index = 2;index<count(next.feedPlate != null);index++) {
            var part = body(index, next);
            if (part == null) {
                if (fittings.containsKey(index)) return reject(index);
                continue;
            }
            newEnds.put(index, TubeFitting.terminal(part, index).getOrigin().add(origin));
            if (!part.equals(before.get(index))) {
                if (!placed(level, player, hanger, part)) return reject(index);
                var old = before.get(index);
                if (old != null && !placed(level, player, hanger, old)) return reject(index);
            }
            if (index>=FIRST_CONNECTOR) {
                for (var other : fitted.values()) if (overlap(other, part)) return reject(index);
                fitted.put(index, part);
            }
        }
        for (var entry : before.entrySet()) {
            if (!newEnds.containsKey(entry.getKey()) && !placed(level, player, hanger, entry.getValue())) return reject(entry.getKey());
        }
        var companion = SableCompanion.INSTANCE;
        for (int index = 0;index<hanger.terminalCount();index++) {
            var old = hanger.terminal(hanger.getBlockState(), index);
            if (old == null) continue;
            var oldEnd = old.getOrigin().add(origin);
            var nextEnd = newEnds.get(index);
            if (nextEnd != null && oldEnd.distanceToSqr(nextEnd)<1e-10) continue;
            var endpoint = new BlockWireEndpoint(hanger.getBlockPos(), index);
            var wires = new LinkedHashSet<>(electric.getConnections().getOrDefault(endpoint, Set.of()));
            var segments = GlobalElectricNetworks.getWorldNetworks(level).findConnectedWires(endpoint);
            if (segments != null) for (var segment : segments) {
                if (segment.owner == null) return reject(index);
                wires.add(segment.owner);
            }
            if (wires.isEmpty()) continue;
            if (nextEnd == null) return reject(index);
            for (var wire : wires) {
                if (wire instanceof ClippedLeadEntity) continue;
                if (wire instanceof BlockWireEntity) return reject(index);
                var other = endpoint.equals(wire.getEndpoint1()) ? wire.getEndpoint2() : wire.getEndpoint1();
                if (other == null) return reject(index);
                if (other instanceof BlockWireEndpoint block && !level.hasChunkAt(block.getPos())) return reject(index);
                var far = other instanceof BlockWireEndpoint block && block.getPos().equals(hanger.getBlockPos())
                        ? newEnds.get(block.getTerminal()) : other.getExactPosition(level);
                if (far == null) return reject(index);
                double distance = nextEnd.distanceTo(companion.projectOutOfSubLevel(level, far));
                var previousFar = companion.projectOutOfSubLevel(level, other.getExactPosition(level));
                if (distance<=oldEnd.distanceTo(previousFar)+.0001) continue;
                var item = wire.getPickResult();
                var type = item == null ? null : WireRegistry.forItem(level, item.getItem());
                if (type == null || distance>type.maximumLength()) return reject(index);
                var saved = wire.saveWithoutId(new CompoundTag());
                if (saved.contains("PlacedLength", Tag.TAG_ANY_NUMERIC)) {
                    if (distance>saved.getFloat("PlacedLength")+.001) return reject(index);
                } else if (Math.round(distance*type.itemsPerMeter())>Math.max(1, wire.getWireCount())) return reject(index);
            }
        }
        return true;
    }
    private static HangerFrame.Member boxBody(AABB box) {
        var center = box.getCenter();
        return new HangerFrame.Member("block", new Vec3(center.x, box.minY, center.z), HangerFrame.UP,
                new Vec3(1, 0, 0), new Vec3(0, 0, 1), box.getYsize(), box.getXsize()/2, box.getZsize()/2, 0, false);
    }

    // Separating-axis test for two oriented boxes. A brace fitting can clear the corner
    // of a wall even when the two axis-aligned bounding boxes overlap.
    static boolean overlap(HangerFrame.Member a, HangerFrame.Member b) {
        var delta = a.point(0, 0, .5).subtract(b.point(0, 0, .5));
        var axesA = new Vec3[]{a.u(), a.v(), a.axis()};
        var axesB = new Vec3[]{b.u(), b.v(), b.axis()};
        for (var axis : axesA) if (separates(axis, delta, a, b)) return false;
        for (var axis : axesB) if (separates(axis, delta, a, b)) return false;
        for (var first : axesA) for (var second : axesB) if (separates(first.cross(second), delta, a, b)) return false;
        return true;
    }
    private static boolean separates(Vec3 axis, Vec3 delta, HangerFrame.Member a, HangerFrame.Member b) {
        double length = axis.length();
        if (length<1e-8) return false;
        return Math.abs(delta.dot(axis)) >= radius(a, axis)+radius(b, axis)-.0001*length;
    }
    private static double radius(HangerFrame.Member box, Vec3 axis) {
        return Math.abs(axis.dot(box.u()))*box.halfU()+Math.abs(axis.dot(box.v()))*box.halfV()
                +Math.abs(axis.dot(box.axis()))*box.length()/2;
    }
    private boolean reject(int index) { rejected = index; return false; }
}
