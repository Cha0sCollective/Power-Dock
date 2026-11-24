package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;

import static org.cha0scollective.powerdock.DockingSession.inWorld;

/** Electrical contacts that follow two facing ports without applying a physics constraint. */
final class AdaptiveDock {
    static final double MAX_GAP = 1.5;
    private static final double MAX_SIDE_OFFSET = 0.75;
    // Keep even the corners of a rotated Pin Dock head forward of its resting face.
    private static final double MIN_GAP = 1.0 / 8;
    private static final double MAX_CONNECT_SPEED = 0.5; // blocks per second
    private static final double MIN_FACING_ALIGNMENT = Math.cos(Math.toRadians(5));

    private AdaptiveDock() {}

    static double distance(ServerLevel level, DockingSession.Ports ports, boolean checkSpeed) {
        var outward = inWorld(ports.targetBody(), ports.targetFrame()).transform(new Vector3d(0, 1, 0));
        var inward = inWorld(ports.approachingBody(), ports.approachFrame()).transform(new Vector3d(0, 1, 0));
        if (!(outward.dot(inward) >= MIN_FACING_ALIGNMENT)) {
            return Double.POSITIVE_INFINITY;
        }

        var target = ports.targetDock().anchor(0.5);
        var approaching = ports.approachingDock().anchor(0.5);
        var start = inWorld(ports.targetBody(), new Vector3d(target));
        var end = inWorld(ports.approachingBody(), new Vector3d(approaching));
        var delta = end.sub(start, new Vector3d());
        double distance = delta.lengthSquared();
        if (!inRange(delta, distance, outward) || !inRange(delta, distance, inward)) {
            return Double.POSITIVE_INFINITY;
        }
        if (checkSpeed) {
            var velocity = Sable.HELPER.getVelocity(level, ports.approachingBody(), approaching, new Vector3d());
            if (ports.targetBody() != null) {
                velocity.sub(Sable.HELPER.getVelocity(level, ports.targetBody(), target, new Vector3d()));
            }
            if (!(velocity.lengthSquared() <= MAX_CONNECT_SPEED * MAX_CONNECT_SPEED)) return Double.POSITIVE_INFINITY;
        }

        // Stay just off the two contact planes when checking the space between them.
        var from = start.fma(0.01, delta);
        var to = end.fma(-0.01, delta);
        var hit = level.clip(new ClipContext(new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? distance : Double.POSITIVE_INFINITY;
    }

    private static boolean inRange(Vector3d delta, double distanceSquared, Vector3d direction) {
        double forward = direction.dot(delta);
        return forward >= MIN_GAP && forward <= MAX_GAP
                && distanceSquared - forward * forward <= MAX_SIDE_OFFSET * MAX_SIDE_OFFSET;
    }
}
