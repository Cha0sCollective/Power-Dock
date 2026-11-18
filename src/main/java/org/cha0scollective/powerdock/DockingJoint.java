package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.cha0scollective.powerdock.DockingSession.inWorld;

/** One mechanical joint shared by every aligned port pair between the same two bodies. */
final class DockingJoint {
    static final double CAPTURE_RANGE = 1.5;
    static final double MIN_CAPTURE_ALIGNMENT = Math.cos(Math.toRadians(30));
    private static final double MAX_APPROACH_SPEED = 2.0;
    private static final double ALIGNMENT_SECONDS = 0.8;

    private final GenericConstraintHandle handle;
    @Nullable private final ServerSubLevel targetBody;
    private final ServerSubLevel approachingBody;
    private final Vector3d approachAnchor;
    private final Vector3d initialAnchor;
    private final Vector3d targetAnchor;
    private final Quaterniond initialFrame;
    private final Quaterniond targetFrame;
    private final Quaterniond finalRotation;
    private final LinkedHashSet<DockingSession> sessions = new LinkedHashSet<>();
    private double alignmentTime;

    private DockingJoint(GenericConstraintHandle handle, DockingSession.Ports ports, Vector3d initialAnchor,
                         Quaterniond initialFrame) {
        this.handle = handle;
        this.targetBody = ports.targetBody();
        this.approachingBody = ports.approachingBody();
        this.approachAnchor = ports.approachAnchor();
        this.initialAnchor = initialAnchor;
        this.targetAnchor = ports.targetAnchor();
        this.initialFrame = initialFrame;
        this.targetFrame = ports.targetFrame();
        this.finalRotation = new Quaterniond(targetFrame).mul(ports.approachFrame().invert());
    }

    static double captureDistance(ServerLevel level, DockingSession.Ports ports) {
        var start = inWorld(ports.approachingBody(), ports.approachAnchor());
        double distance = start.distanceSquared(inWorld(ports.targetBody(), ports.targetAnchor()));
        if (!(distance <= CAPTURE_RANGE * CAPTURE_RANGE)) return Double.POSITIVE_INFINITY;

        var outward = inWorld(ports.targetBody(), ports.targetFrame()).transform(new Vector3d(0, 1, 0));
        var separation = start.sub(inWorld(ports.targetBody(), ports.targetDock().anchor(0.5)), new Vector3d());
        double forward = separation.dot(outward);
        var approach = inWorld(ports.approachingBody(), ports.approachFrame()).transform(new Vector3d(0, 1, 0));
        if (forward < 0 || separation.lengthSquared() - forward * forward > 0.75 * 0.75
                || approach.dot(outward) < MIN_CAPTURE_ALIGNMENT) return Double.POSITIVE_INFINITY;

        var speed = Sable.HELPER.getVelocity(level, ports.approachingBody(), ports.approachAnchor(), new Vector3d());
        if (ports.targetBody() != null) {
            speed.sub(Sable.HELPER.getVelocity(level, ports.targetBody(), ports.targetDock().anchor(0.5), new Vector3d()));
        }
        return speed.lengthSquared() <= MAX_APPROACH_SPEED * MAX_APPROACH_SPEED ? distance : Double.POSITIVE_INFINITY;
    }

    double matchDistance(DockingSession.Ports ports) {
        // Check where the additional pair will sit when the original joint reaches its final pose.
        var expectedAnchor = finalRotation.transform(ports.approachAnchor().sub(approachAnchor)).add(targetAnchor);
        var expectedFrame = ports.approachFrame().premul(finalRotation);
        if (!(expectedAnchor.distanceSquared(ports.targetAnchor()) <= DockingSession.CONTACT_TOLERANCE_SQUARED
                && Math.abs(expectedFrame.dot(ports.targetFrame())) >= DockingSession.MIN_CONTACT_ALIGNMENT)) {
            return Double.POSITIVE_INFINITY;
        }
        return inWorld(ports.approachingBody(), ports.approachAnchor())
                .distanceSquared(inWorld(ports.targetBody(), ports.targetAnchor()));
    }

    @Nullable
    static DockingJoint start(ServerLevel level, DockingSession.Ports ports) {
        var initialAnchor = inWorld(ports.approachingBody(), ports.approachAnchor());
        var initialFrame = inWorld(ports.approachingBody(), ports.approachFrame());
        if (ports.targetBody() != null) {
            // Sable expects the second joint frame in target hull coordinates when it can move.
            var pose = ports.targetBody().logicalPose();
            pose.transformPositionInverse(initialAnchor);
            initialFrame.premul(new Quaterniond(pose.orientation()).invert());
        }
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        var handle = pipeline.addConstraint(ports.approachingBody(), ports.targetBody(), new GenericConstraintConfiguration(
                ports.approachAnchor(), initialAnchor, ports.approachFrame(), initialFrame,
                EnumSet.allOf(ConstraintJointAxis.class)));
        return handle == null ? null : new DockingJoint(handle, ports, initialAnchor, initialFrame);
    }

    void attach(DockingSession session) {
        sessions.add(session);
    }

    void detach(DockingSession session) {
        sessions.remove(session);
        if (sessions.isEmpty() && handle.isValid()) handle.remove();
    }

    void advance(DockingSession session, ServerSubLevel body, double timeStep) {
        if (body != approachingBody || aligned() || sessions.isEmpty() || sessions.getFirst() != session || !isValid()) return;
        alignmentTime = Math.min(alignmentTime + timeStep, ALIGNMENT_SECONDS);
        double fraction = alignmentTime / ALIGNMENT_SECONDS;
        double eased = fraction * fraction * (3 - 2 * fraction);
        handle.setFrame2(initialAnchor.lerp(targetAnchor, eased, new Vector3d()),
                initialFrame.slerp(targetFrame, eased, new Quaterniond()));
    }

    boolean aligned() {
        return alignmentTime >= ALIGNMENT_SECONDS;
    }

    boolean isValid() {
        return !approachingBody.isRemoved() && (targetBody == null || !targetBody.isRemoved()) && handle.isValid();
    }

    boolean targets(@Nullable ServerSubLevel body) {
        return targetBody == body;
    }

    @Nullable
    static DockingJoint between(@Nullable ServerSubLevel first, ServerSubLevel second) {
        for (var actor : second.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof PowerDockBlockEntity dock)) continue;
            var joint = dock.currentJoint();
            if (joint != null && ((joint.targetBody == first && joint.approachingBody == second)
                    || (joint.targetBody == second && joint.approachingBody == first))) return joint;
        }
        return null;
    }

    static boolean canConnect(@Nullable ServerSubLevel first, ServerSubLevel second) {
        // Existing Power Dock joints must stay a tree, including paths through the stationary world.
        var reachable = new HashSet<ServerSubLevel>();
        boolean anchored = collectMovingBodies(second, reachable);
        if (first == null) return !anchored;
        return !reachable.contains(first) && (!anchored || !collectMovingBodies(first, new HashSet<>()));
    }

    private static boolean collectMovingBodies(ServerSubLevel start, Set<ServerSubLevel> visited) {
        var queue = new ArrayDeque<ServerSubLevel>();
        queue.add(start);
        visited.add(start);
        boolean anchored = false;
        while (!queue.isEmpty()) {
            var body = queue.removeFirst();
            for (var actor : body.getPlot().getBlockEntityActors()) {
                if (!(actor instanceof PowerDockBlockEntity dock)) continue;
                var joint = dock.currentJoint();
                if (joint == null || !joint.isValid() || (joint.targetBody != body && joint.approachingBody != body)) continue;
                var neighbor = joint.targetBody == body ? joint.approachingBody : joint.targetBody;
                if (neighbor == null) anchored = true;
                else if (visited.add(neighbor)) queue.addLast(neighbor);
            }
        }
        return anchored;
    }
}
