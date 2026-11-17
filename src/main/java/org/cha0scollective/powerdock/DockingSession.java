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
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.EnumSet;

/** Holds one ship against one station and owns their temporary electrical contacts. */
final class DockingSession {
    static final double CAPTURE_RANGE = 1.5;
    private static final double SHIP_ANCHOR = 0.4375;
    // Two half-block housings and two extended arms meet at the mating plane.
    private static final double STATION_ANCHOR = 1.0 + 2 * PowerDockBlockEntity.ARM_TRAVEL - SHIP_ANCHOR;
    private static final double ALIGNMENT_SECONDS = 0.8;
    private static final double MAX_APPROACH_SPEED = 2.0;
    private static final double MIN_CAPTURE_ALIGNMENT = Math.cos(Math.toRadians(30));
    private static final double MIN_CONTACT_ALIGNMENT = Math.cos(Math.toRadians(5) / 2);
    private static final float CONTACT_RESISTANCE = 0.05f;

    private final ServerLevel level;
    private final PowerDockBlockEntity station;
    private final Capture capture;
    private final GenericConstraintHandle joint;
    private final long alignmentDeadline;
    private final ElectricWire[] contacts = new ElectricWire[PowerDockBlock.TERMINAL_COUNT];
    private double alignmentTime;
    private long deployTick = Long.MAX_VALUE;
    private boolean conducting;
    private boolean released;

    private DockingSession(ServerLevel level, PowerDockBlockEntity station, Capture capture,
                           GenericConstraintHandle joint) {
        this.level = level;
        this.station = station;
        this.capture = capture;
        this.joint = joint;
        this.alignmentDeadline = level.getGameTime() + 50;
    }

    static Capture findCapture(ServerLevel level, PowerDockBlockEntity station, PowerDockBlockEntity ship,
                               ServerSubLevel body) {
        var pose = body.logicalPose();
        var shipAnchor = ship.anchor(SHIP_ANCHOR);
        var start = pose.transformPosition(shipAnchor, new Vector3d());
        var target = station.anchor(STATION_ANCHOR);
        double distance = start.distanceSquared(target);
        if (!(distance <= CAPTURE_RANGE * CAPTURE_RANGE)) return null;

        // The joint points into the ship. Match model roll so the keyed cable lanes meet.
        var shipFrame = new Quaterniond(PowerDockBlock.modelRotation(ship.facing())).rotateX(Math.PI / 2);
        var initialFrame = new Quaterniond(pose.orientation()).mul(shipFrame);
        var finalFrame = new Quaterniond(PowerDockBlock.modelRotation(station.facing()))
                .rotateY(Math.PI).rotateX(Math.PI / 2);
        var outward = finalFrame.transform(new Vector3d(0, 1, 0));
        var separation = start.sub(station.anchor(0.5), new Vector3d());
        double forward = separation.dot(outward);
        if (forward < 0 || separation.lengthSquared() - forward * forward > 0.75 * 0.75
                || initialFrame.transform(new Vector3d(0, 1, 0)).dot(outward) < MIN_CAPTURE_ALIGNMENT) return null;

        var speed = Sable.HELPER.getVelocity(level, body, shipAnchor, new Vector3d());
        if (!(speed.lengthSquared() <= MAX_APPROACH_SPEED * MAX_APPROACH_SPEED)) return null;

        return new Capture(ship, body, shipAnchor, shipFrame, start, initialFrame, target, finalFrame, distance);
    }

    record Capture(PowerDockBlockEntity ship, ServerSubLevel body, Vector3d shipAnchor, Quaterniond shipFrame,
                   Vector3d start, Quaterniond initialFrame, Vector3d target, Quaterniond finalFrame,
                   double distanceSquared) {
        DockingSession start(ServerLevel level, PowerDockBlockEntity station) {
            var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
            // Start at the current pose, then draw the frames together over successive physics steps.
            // Keep contacts enabled so terrain can block an obstructed approach.
            var joint = pipeline.addConstraint(body, null, new GenericConstraintConfiguration(
                    shipAnchor, start, shipFrame, initialFrame, EnumSet.allOf(ConstraintJointAxis.class)));
            return new DockingSession(level, station, this, joint);
        }
    }

    void advance(ServerSubLevel body, double timeStep) {
        if (body != capture.body || !joint.isValid() || alignmentTime >= ALIGNMENT_SECONDS) return;
        alignmentTime = Math.min(alignmentTime + timeStep, ALIGNMENT_SECONDS);
        double fraction = alignmentTime / ALIGNMENT_SECONDS;
        double eased = fraction * fraction * (3 - 2 * fraction);
        joint.setFrame2(capture.start.lerp(capture.target, eased, new Vector3d()),
                capture.initialFrame.slerp(capture.finalFrame, eased, new Quaterniond()));
    }

    boolean update() {
        if (!joint.isValid() || !station.ready(level) || !ship().ready(level)
                || Sable.HELPER.getContaining(ship()) != capture.body
                || Sable.HELPER.isInPlotGrid(level, station.getBlockPos())) return false;

        // A wrench rotation changes the mating port even if the block entity survives.
        if (ship().anchor(SHIP_ANCHOR).distanceSquared(capture.shipAnchor) > 0.0001
                || station.anchor(STATION_ANCHOR).distanceSquared(capture.target) > 0.0001) return false;

        var pose = capture.body.logicalPose();
        var actual = pose.transformPosition(capture.shipAnchor, new Vector3d());
        double error = actual.distanceSquared(capture.target);
        var orientation = new Quaterniond(pose.orientation()).mul(capture.shipFrame);
        double alignment = Math.abs(orientation.dot(capture.finalFrame));

        if (conducting) {
            if (!(error <= 0.25 * 0.25 && alignment >= MIN_CAPTURE_ALIGNMENT)) return false;
            for (var contact : contacts) {
                if (contact.getNetwork() == null) return false;
            }
            return true;
        }
        var shipFace = pose.transformPosition(ship().anchor(0.5), new Vector3d());
        boolean inReach = shipFace.distanceSquared(station.anchor(0.5)) < 1;
        if (inReach != (deployTick != Long.MAX_VALUE)) {
            deployTick = inReach ? level.getGameTime() : Long.MAX_VALUE;
            station.showDockState(inReach, false);
            ship().showDockState(inReach, false);
        }
        if (alignmentTime >= ALIGNMENT_SECONDS && inReach
                && level.getGameTime() >= deployTick + PowerDockBlockEntity.DEPLOY_TICKS
                && error <= 0.08 * 0.08 && alignment >= MIN_CONTACT_ALIGNMENT) {
            return closeContacts();
        }
        return level.getGameTime() < alignmentDeadline;
    }

    private boolean closeContacts() {
        var world = GlobalElectricNetworks.getWorldNetworks(level);
        for (int pin = 0; pin < contacts.length; pin++) {
            contacts[pin] = world.makeSimpleWire(new BlockWireEndpoint(station.getBlockPos(), pin),
                    new BlockWireEndpoint(ship().getBlockPos(), pin), CONTACT_RESISTANCE);
            if (contacts[pin] == null) return false;
        }
        conducting = true;
        station.showDockState(true, true);
        if (!released) ship().showDockState(true, true);
        return !released;
    }

    void release() {
        if (released) return;
        released = true;
        conducting = false;
        station.clearDocking(this);
        ship().clearDocking(this);

        for (int pin = 0; pin < contacts.length; pin++) {
            var contact = contacts[pin];
            if (contact == null) continue;
            var owner = contact.getNetwork();
            contact.remove();
            if (owner != null) GlobalElectricNetworks.getWorldNetworks(level).scheduleIslandDiscovery(owner);
            contacts[pin] = null;
        }
        if (joint.isValid()) joint.remove();
        station.showDockState(false, false);
        ship().showDockState(false, false);
    }

    PowerDockBlockEntity station() {
        return station;
    }

    PowerDockBlockEntity ship() {
        return capture.ship;
    }

    boolean conducting() {
        return conducting;
    }
}
