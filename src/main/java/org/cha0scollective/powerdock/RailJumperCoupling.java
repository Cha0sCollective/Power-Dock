package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@EventBusSubscriber(modid = PowerDock.ID)
final class RailJumperCoupling {
    // Share the loaded block-entity scan across waiting jumpers for this server tick.
    private static final Map<ServerSubLevel, Map<Knuckle, List<RailJumperBlockEntity>>> ENDS = new IdentityHashMap<>();
    private static final Set<Knuckle> MATCHED = new HashSet<>();

    private RailJumperCoupling() {}

    @SubscribeEvent
    public static void clear(ServerTickEvent.Post event) {
        ENDS.clear();
        MATCHED.clear();
    }

    static void tryConnect(ServerLevel level, RailJumperBlockEntity jumper) {
        if (!(Sable.HELPER.getContaining(jumper) instanceof ServerSubLevel body) || body.isRemoved()) return;
        var ends = ENDS.computeIfAbsent(body, RailJumperCoupling::findEnds);
        for (var entry : ends.entrySet()) {
            if (!entry.getValue().contains(jumper)) continue;
            var knuckle = entry.getKey();
            if (MATCHED.add(knuckle)) {
                var mate = knuckle.partner(level);
                if (mate != null && MATCHED.add(mate)) pair(level, knuckle, mate);
            }
            return;
        }
    }

    private static Map<Knuckle, List<RailJumperBlockEntity>> findEnds(ServerSubLevel carriage) {
        if (!LoconauticsCompatibility.isRailCarriage(carriage)) return Map.of();
        var knuckles = new ArrayList<Knuckle>();
        var jumpers = new ArrayList<RailJumperBlockEntity>();
        for (var holder : carriage.getPlot().getLoadedChunks()) {
            for (var entity : holder.getChunk().getBlockEntities().values()) {
                if (entity.isRemoved()) continue;
                if (entity instanceof RailJumperBlockEntity jumper) {
                    jumpers.add(jumper);
                } else if (LoconauticsCompatibility.isKnuckle(entity)) {
                    knuckles.add(new Knuckle(carriage, entity.getBlockPos(),
                            entity.getBlockState().getValue(RailJumperBlock.FACING)));
                }
            }
        }
        var ends = new HashMap<Knuckle, List<RailJumperBlockEntity>>();
        for (var jumper : jumpers) {
            Knuckle nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            var outward = jumper.facing();
            for (var knuckle : knuckles) {
                if (knuckle.outward != outward) continue;
                double distance = knuckle.pos.distSqr(jumper.getBlockPos());
                if (distance < nearestDistance || (distance == nearestDistance && knuckle.pos.compareTo(nearest.pos) < 0)) {
                    nearest = knuckle;
                    nearestDistance = distance;
                }
            }
            if (nearest != null) ends.computeIfAbsent(nearest, unused -> new ArrayList<>()).add(jumper);
        }
        return ends;
    }

    private static void pair(ServerLevel level, Knuckle end, Knuckle mate) {
        // Use the same left-to-right order from either car. Relative positions never swing with the train.
        boolean reverse = end.pos.compareTo(mate.pos) > 0;
        var first = slots(level, end, reverse);
        var second = slots(level, mate, !reverse);
        if (first.size() > second.size()) {
            var swap = first;
            first = second;
            second = swap;
            var swapEnd = end;
            end = mate;
            mate = swapEnd;
        }
        int next = 0;
        for (int i = 0; i < first.size(); i++) {
            var from = first.get(i);
            int last = second.size() - first.size() + i;
            int best = next;
            for (int j = next + 1; j <= last; j++) {
                if (from.distanceTo(second.get(j)) < from.distanceTo(second.get(best))) best = j;
            }
            RailJumperBlockEntity.connect(level, from.jumper, second.get(best).jumper, end, mate);
            next = best + 1;
        }
    }

    private static List<Slot> slots(ServerLevel level, Knuckle knuckle, boolean reverse) {
        var jumpers = ENDS.computeIfAbsent(knuckle.body, RailJumperCoupling::findEnds).getOrDefault(knuckle, List.of());
        var slots = new ArrayList<Slot>();
        var lateralAxis = knuckle.outward.getClockWise();
        if (reverse) lateralAxis = lateralAxis.getOpposite();
        for (var jumper : jumpers) {
            if (jumper.connected() || !knuckle.owns(level, jumper)) continue;
            var delta = jumper.getBlockPos().subtract(knuckle.pos);
            slots.add(new Slot(jumper, delta.getX() * lateralAxis.getStepX() + delta.getZ() * lateralAxis.getStepZ(),
                    delta.getY(), delta.getX() * knuckle.outward.getStepX() + delta.getZ() * knuckle.outward.getStepZ()));
        }
        slots.sort(Comparator.comparingInt(Slot::lateral).thenComparingInt(Slot::height).thenComparingInt(Slot::depth));
        return slots;
    }

    private record Slot(RailJumperBlockEntity jumper, int lateral, int height, int depth) {
        double distanceTo(Slot other) {
            double x = lateral - other.lateral;
            double y = height - other.height;
            double z = depth - other.depth;
            return x * x + y * y + z * z;
        }
    }

    record Knuckle(ServerSubLevel body, BlockPos pos, Direction outward) {
        boolean owns(ServerLevel level, RailJumperBlockEntity jumper) {
            return !body.isRemoved() && jumper.ready(level) && jumper.facing() == outward
                    && Sable.HELPER.getContaining(jumper) == body;
        }

        @Nullable
        private LoconauticsCompatibility.CoupledEnd connectedEnd(ServerLevel level) {
            if (body.isRemoved() || !level.hasChunkAt(pos) || Sable.HELPER.getContaining(level, pos) != body) return null;
            var entity = level.getBlockEntity(pos);
            if (!LoconauticsCompatibility.isKnuckle(entity)
                    || entity.getBlockState().getValue(RailJumperBlock.FACING) != outward) return null;
            return LoconauticsCompatibility.coupledEnd(entity);
        }

        boolean linksTo(ServerLevel level, Knuckle other) {
            var end = connectedEnd(level);
            return end != null && end.bodyId().equals(other.body.getUniqueId()) && end.knucklePos().equals(other.pos);
        }

        @Nullable
        Knuckle partner(ServerLevel level) {
            var end = connectedEnd(level);
            if (end == null || end.bodyId().equals(body.getUniqueId())) return null;
            var container = SubLevelContainer.getContainer(level);
            if (container == null || !(container.getSubLevel(end.bodyId()) instanceof ServerSubLevel remote)
                    || remote.isRemoved() || !level.hasChunkAt(end.knucklePos())) return null;
            var entity = level.getBlockEntity(end.knucklePos());
            if (!LoconauticsCompatibility.isKnuckle(entity)) return null;
            var mate = new Knuckle(remote, end.knucklePos(), entity.getBlockState().getValue(RailJumperBlock.FACING));
            return mate.linksTo(level, this) ? mate : null;
        }
    }
}
