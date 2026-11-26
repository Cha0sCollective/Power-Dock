package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

record HangerTrackTarget(BlockPos rail, CatenarySpan.Head frame) {
    static final int INITIAL_HEIGHT = 4;
    private static final double CURVE_REACH = 0.4375;

    @Nullable
    static HangerTrackTarget pick(ServerLevel level, Player player) {
        var eye = player.getEyePosition();
        var direction = player.calculateViewVector(player.getXRot(), player.getYRot());
        var end = eye.add(direction.scale(player.blockInteractionRange()));
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && level.getBlockState(hit.getBlockPos()).getBlock() instanceof ITrackBlock) {
            var selected = forRail(level, hit.getBlockPos());
            if (selected != null) return selected;
        }

        double visible = hit.getLocation().distanceTo(eye);
        var rayEnd = eye.add(direction.scale(visible));
        CurveAim best = null;
        for (var candidate : CatenaryTrackIndex.curvesNear(level, new AABB(eye, rayEnd).inflate(CURVE_REACH))) {
            var aim = aimAtCurve(candidate.connection(), eye, direction, visible);
            if (aim != null && (best == null || aim.distanceSqr < best.distanceSqr)) best = aim;
        }
        if (best == null) return null;
        var selected = forRail(level, BlockPos.containing(best.point));
        return selected != null && selected.frame.center().distanceToSqr(best.point) < 0.75 * 0.75 ? selected : null;
    }

    @Nullable
    static CurveAim aimAtCurve(BezierConnection curve, Vec3 eye, Vec3 direction, double visible) {
        int samples = Math.clamp(curve.getSegmentCount() * 2, 8, 1024);
        double step = 1.0 / samples;
        double parameter = 0;
        double best = CURVE_REACH * CURVE_REACH;
        boolean found = false;
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double distance = rayDistance(eye, direction, visible, curve.getPosition(t));
            if (distance < best) {
                best = distance;
                parameter = t;
                found = true;
            }
        }
        if (!found) return null;
        // Refine the curve hit so nearby sample points do not choose the wrong block at a cell boundary.
        double low = Math.max(0, parameter - step);
        double high = Math.min(1, parameter + step);
        for (int i = 0; i < 12; i++) {
            double third = (high - low) / 3;
            if (rayDistance(eye, direction, visible, curve.getPosition(low + third))
                    <= rayDistance(eye, direction, visible, curve.getPosition(high - third))) high -= third;
            else low += third;
        }
        var closest = curve.getPosition((low + high) * 0.5);
        double refined = rayDistance(eye, direction, visible, closest);
        return refined <= best ? new CurveAim(closest, refined) : new CurveAim(curve.getPosition(parameter), best);
    }

    private static double rayDistance(Vec3 eye, Vec3 direction, double visible, Vec3 point) {
        var delta = point.subtract(eye);
        double along = delta.dot(direction);
        return along < 0 || along > visible + 0.0625 ? Double.POSITIVE_INFINITY
                : delta.subtract(direction.scale(along)).lengthSqr();
    }

    @Nullable
    private static HangerTrackTarget forRail(ServerLevel level, BlockPos rail) {
        if (!level.hasChunkAt(rail)) return null;
        var probe = level.getBlockState(rail).getBlock() instanceof ITrackBlock ? rail.above() : rail.above(2);
        var frame = CatenaryRoute.placementHead(level, probe);
        return frame == null ? null : new HangerTrackTarget(rail, frame);
    }

    BlockPos overheadPosition() {
        return rail.atY(Mth.floor(frame.center().y + INITIAL_HEIGHT + HangerFixture.BEAM_RISE));
    }

    @Nullable
    CatenaryHangerBlockEntity hanger(ServerLevel level) {
        return mountedHanger(level, rail, frame.center());
    }

    @Nullable
    static CatenaryHangerBlockEntity mountedHanger(Level level, BlockPos rail, Vec3 center) {
        if (!level.hasChunkAt(rail)) return null;
        for (int y = rail.getY(); y <= rail.getY() + 16; y++) {
            var pos = rail.atY(y);
            if (level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger && !hanger.isRemoved()
                    && hanger.mount() != null) {
                var mountedRail = Vec3.atLowerCornerOf(pos).add(hanger.mount().rail);
                if (mountedRail.distanceToSqr(center) < 0.75 * 0.75) return hanger;
            }
        }
        return null;
    }

    record CurveAim(Vec3 point, double distanceSqr) {}
}
