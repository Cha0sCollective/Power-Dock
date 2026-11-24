package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import javax.annotation.Nullable;

/** The arms and electrical contacts of one pair of ports. */
final class DockingSession {
    static final double MIN_CONTACT_ALIGNMENT = Math.cos(Math.toRadians(5) / 2);
    static final double CONTACT_TOLERANCE_SQUARED = 0.08 * 0.08;
    private static final float CONTACT_RESISTANCE = 0.05f;
    private static final double APPROACH_ANCHOR = 0.4375;
    // The combined anchor offsets put the two extended heads on the same plane.
    private static final double TARGET_ANCHOR = 1.0 + 2 * PowerDockBlockEntity.ARM_TRAVEL - APPROACH_ANCHOR;

    private final ServerLevel level;
    private final Ports ports;
    @Nullable private final DockingJoint joint;
    private final Direction targetFacing;
    private final Direction approachingFacing;
    private final Vector3d approachAnchor;
    private final Vector3d targetAnchor;
    private final Quaterniond approachFrame;
    private final Quaterniond targetFrame;
    private final long alignmentDeadline;
    private final ElectricWire[] contacts;
    @Nullable private DockComputer targetComputer;
    @Nullable private DockComputer approachingComputer;
    private long deployTick = Long.MAX_VALUE;
    private boolean conducting;
    private boolean released;

    DockingSession(ServerLevel level, Ports ports, @Nullable DockingJoint joint) {
        this.level = level;
        this.ports = ports;
        this.joint = joint;
        this.contacts = new ElectricWire[ports.targetDock.contactCount()];
        this.targetFacing = ports.targetDock.facing();
        this.approachingFacing = ports.approachingDock.facing();
        this.approachAnchor = ports.approachAnchor();
        this.targetAnchor = ports.targetAnchor();
        this.approachFrame = ports.approachFrame();
        this.targetFrame = ports.targetFrame();
        this.alignmentDeadline = level.getGameTime() + 50;
        if (joint != null) joint.attach(this);
    }

    void start() {
        if (joint == null) {
            deployTick = level.getGameTime();
            ports.targetDock.setAdaptivePartner(ports.approachingDock.getBlockPos());
            ports.approachingDock.setAdaptivePartner(ports.targetDock.getBlockPos());
            ports.targetDock.showDockState(true, false);
            if (!released) ports.approachingDock.showDockState(true, false);
        }
    }

    boolean update() {
        if (!ports.targetDock.ready(level) || !ports.approachingDock.ready(level)
                || Sable.HELPER.getContaining(ports.targetDock) != ports.targetBody
                || Sable.HELPER.getContaining(ports.approachingDock) != ports.approachingBody
                || ports.targetDock.facing() != targetFacing || ports.approachingDock.facing() != approachingFacing) return false;

        if (joint == null) {
            if (!Double.isFinite(AdaptiveDock.distance(level, ports, !conducting))) return false;
            if (conducting) return contactsIntact();
            return level.getGameTime() < deployTick + PowerDockBlockEntity.DEPLOY_TICKS || closeContacts();
        }
        if (!joint.isValid() || (level.getGameTime() % 4 == 0
                && (LoconauticsCompatibility.isRailCarriage(ports.targetBody)
                || LoconauticsCompatibility.isRailCarriage(ports.approachingBody)))) return false;

        var approachPose = ports.approachingBody.logicalPose();
        double error = approachPose.transformPosition(approachAnchor, new Vector3d())
                .distanceSquared(inWorld(ports.targetBody, new Vector3d(targetAnchor)));
        var orientation = new Quaterniond(approachPose.orientation()).mul(approachFrame);
        double alignment = Math.abs(orientation.dot(inWorld(ports.targetBody, new Quaterniond(targetFrame))));

        if (conducting) {
            if (!(error <= 0.25 * 0.25 && alignment >= DockingJoint.MIN_CAPTURE_ALIGNMENT)) return false;
            return contactsIntact();
        }
        var approachFace = approachPose.transformPosition(ports.approachingDock.anchor(0.5));
        boolean inReach = approachFace.distanceSquared(inWorld(ports.targetBody, ports.targetDock.anchor(0.5))) < 1;
        if (inReach != (deployTick != Long.MAX_VALUE)) {
            deployTick = inReach ? level.getGameTime() : Long.MAX_VALUE;
            ports.targetDock.showDockState(inReach, false);
            if (released) return false;
            ports.approachingDock.showDockState(inReach, false);
            if (released) return false;
        }
        if (joint.aligned() && inReach && level.getGameTime() >= deployTick + PowerDockBlockEntity.DEPLOY_TICKS
                && error <= CONTACT_TOLERANCE_SQUARED && alignment >= MIN_CONTACT_ALIGNMENT) {
            return closeContacts();
        }
        return level.getGameTime() < alignmentDeadline;
    }

    private boolean contactsIntact() {
        for (var contact : contacts) {
            if (contact.getNetwork() == null) return false;
        }
        return true;
    }

    private boolean closeContacts() {
        var world = GlobalElectricNetworks.getWorldNetworks(level);
        for (int pin = 0; pin < contacts.length; pin++) {
            contacts[pin] = world.makeSimpleWire(new BlockWireEndpoint(ports.targetDock.getBlockPos(), pin),
                    new BlockWireEndpoint(ports.approachingDock.getBlockPos(), pin), CONTACT_RESISTANCE);
            if (contacts[pin] == null) return false;
        }
        conducting = true;
        if (PowerDock.computerCraft && ports.targetDock.isPinDock()) {
            targetComputer = ports.targetDock.computer(level);
            approachingComputer = ports.approachingDock.computer(level);
            targetComputer.connect(approachingComputer);
        }
        ports.targetDock.showDockState(true, true);
        if (!released) ports.approachingDock.showDockState(true, true);
        return !released;
    }

    void release() {
        if (released) return;
        released = true;
        conducting = false;
        ports.targetDock.clearDocking(this);
        ports.approachingDock.clearDocking(this);

        if (targetComputer != null && approachingComputer != null) targetComputer.disconnect(approachingComputer);

        for (int pin = 0; pin < contacts.length; pin++) {
            var contact = contacts[pin];
            if (contact == null) continue;
            var owner = contact.getNetwork();
            contact.remove();
            if (owner != null) GlobalElectricNetworks.getWorldNetworks(level).scheduleIslandDiscovery(owner);
            contacts[pin] = null;
        }
        if (joint != null) joint.detach(this);
        ports.targetDock.showDockState(false, false);
        ports.approachingDock.showDockState(false, false);
        if (joint == null) {
            ports.targetDock.setAdaptivePartner(null);
            ports.approachingDock.setAdaptivePartner(null);
        }
    }

    PowerDockBlockEntity targetDock() {
        return ports.targetDock;
    }

    PowerDockBlockEntity approachingDock() {
        return ports.approachingDock;
    }

    @Nullable DockingJoint joint() {
        return joint;
    }

    boolean conducting() {
        return conducting;
    }

    static Vector3d inWorld(@Nullable ServerSubLevel body, Vector3d position) {
        return body == null ? position : body.logicalPose().transformPosition(position);
    }

    static Quaterniond inWorld(@Nullable ServerSubLevel body, Quaterniond orientation) {
        return body == null ? orientation : orientation.premul(body.logicalPose().orientation());
    }

    record Ports(PowerDockBlockEntity targetDock, @Nullable ServerSubLevel targetBody,
                 PowerDockBlockEntity approachingDock, ServerSubLevel approachingBody) {
        Vector3d approachAnchor() {
            return approachingDock.anchor(APPROACH_ANCHOR);
        }

        Vector3d targetAnchor() {
            return targetDock.anchor(TARGET_ANCHOR);
        }

        Quaterniond approachFrame() {
            return new Quaterniond(PowerDockBlock.modelRotation(approachingDock.facing())).rotateX(Math.PI / 2);
        }

        Quaterniond targetFrame() {
            // Match the two keyed faces so positive meets positive and negative meets negative.
            return new Quaterniond(PowerDockBlock.modelRotation(targetDock.facing()))
                    .rotateY(Math.PI).rotateX(Math.PI / 2);
        }
    }
}
