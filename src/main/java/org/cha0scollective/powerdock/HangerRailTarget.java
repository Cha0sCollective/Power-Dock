package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

record HangerRailTarget(CatenaryHangerBlockEntity hanger, BlockPos rail, Vec3 center, Vec3 direction,
                        double halfLength, double hitDistance) {
    private static final double MOUNT_TOLERANCE = 1.0 / 16;

    AABB outline() {
        return new AABB(-0.515625, -0.03125, -halfLength, 0.515625, 0.1875, halfLength);
    }

    @Nullable
    static HangerRailTarget pick(ServerLevel level, HangerTrackTarget.TrackRay ray, boolean curvedOnly) {
        if (!curvedOnly && ray.hit().getType() == HitResult.Type.BLOCK) {
            var straight = onStraight(level, ray.hit().getBlockPos());
            if (straight != null) return straight;
        }

        var end = ray.eye().add(ray.direction().scale(ray.visible()));
        var nearby = new AABB(ray.eye(), end).inflate(1);
        HangerRailTarget best = null;
        for (var candidate : CatenaryTrackIndex.curvesNear(level, nearby)) {
            var target = onCurve(level, candidate.connection(), ray);
            if (target != null && (best == null || target.hitDistance < best.hitDistance)) best = target;
        }
        return best;
    }

    @Nullable
    static HangerRailTarget onStraight(Level level, BlockPos pos) {
        if (Sable.HELPER.isInPlotGrid(level, pos) || !level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return null;
        var axes = track.getTrackAxes(level, pos, state);
        if (axes.size() != 1) return null;
        var center = Vec3.atBottomCenterOf(pos).add(0, track.getElevationAtCenter(level, pos, state), 0);
        var hanger = HangerTrackTarget.mountedHanger(level, pos, center);
        if (hanger == null) return null;
        var axis = axes.getFirst();
        return new HangerRailTarget(hanger, pos, center, axis, axis.length() * 0.5, 0);
    }

    @Nullable
    static HangerRailTarget onCurve(Level level, BezierConnection curve, HangerTrackTarget.TrackRay ray) {
        var end = ray.eye().add(ray.direction().scale(ray.visible()));
        var nearby = new AABB(ray.eye(), end).inflate(1);
        var curveBounds = curve.getBounds().inflate(MOUNT_TOLERANCE);
        HangerRailTarget best = null;
        for (int x = chunk(nearby.minX); x <= chunk(nearby.maxX); x++) {
            for (int z = chunk(nearby.minZ); z <= chunk(nearby.maxZ); z++) {
                for (var pos : FrameInteraction.hangersInChunk(level, x, z)) {
                    if (!level.hasChunkAt(pos)
                            || !(level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger)) continue;
                    var mount = hanger.mount();
                    if (mount == null || hanger.isRemoved()) continue;
                    var center = Vec3.atLowerCornerOf(pos).add(mount.rail);
                    if (!nearby.contains(center) || !curveBounds.contains(center)) continue;
                    var target = intersectCurve(hanger, mount, center, curve, ray.eye(), end);
                    if (target != null && (best == null || target.hitDistance < best.hitDistance)) best = target;
                }
            }
        }
        return best;
    }

    private static int chunk(double coordinate) {
        return SectionPos.blockToSectionCoord(Mth.floor(coordinate));
    }

    @Nullable
    private static HangerRailTarget intersectCurve(CatenaryHangerBlockEntity hanger, HangerMount mount, Vec3 center,
                                                  BezierConnection curve, Vec3 eye, Vec3 end) {
        // Verify the saved mount is on this curve, including at crossings or on stacked tracks.
        double t = CatenaryRoute.closestParameter(curve, center);
        if (curve.getPosition(t).distanceToSqr(center) > MOUNT_TOLERANCE * MOUNT_TOLERANCE) return null;
        double step = 0.5 / curve.getLength();
        var direction = curve.getPosition(Math.min(1, t + step))
                .subtract(curve.getPosition(Math.max(0, t - step))).normalize();
        var across = new Vec3(direction.z, 0, -direction.x).normalize();
        if (Math.abs(across.dot(new Vec3(Math.cos(mount.yaw), 0, -Math.sin(mount.yaw)))) < 0.99) return null;
        var up = direction.cross(across);
        var start = local(eye.subtract(center), across, up, direction);
        var finish = local(end.subtract(center), across, up, direction);
        var rail = BlockPos.containing(center);
        var target = new HangerRailTarget(hanger, rail, center, direction, 0.5, 0);
        var outline = target.outline();
        if (outline.contains(start)) return target;
        var hit = outline.clip(start, finish).orElse(null);
        return hit == null ? null : new HangerRailTarget(hanger, rail, center, direction, 0.5, start.distanceToSqr(hit));
    }

    private static Vec3 local(Vec3 offset, Vec3 across, Vec3 up, Vec3 direction) {
        return new Vec3(offset.dot(across), offset.dot(up), offset.dot(direction));
    }
}
