package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;

import javax.annotation.Nullable;
import java.util.Objects;

public final class PowerDockBlockEntity extends ElectricBlockEntity implements BlockEntitySubLevelActor {
    static final double ARM_TRAVEL = 11.0 / 32;
    static final int DEPLOY_TICKS = 6;
    private DockingSession docking;
    private long nextCaptureTick;
    private long retractionEndTick;
    private int extension;
    private int previousExtension;
    private Control control = Control.REDSTONE;
    @Nullable private DockComputer computer;

    PowerDockBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.DOCK_ENTITY.get(), pos, state);
        extension = previousExtension = state.getValue(PowerDockBlock.DEPLOYED) ? DEPLOY_TICKS : 0;
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(contactCount());
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) {
            if (!drawsMovingArm()) {
                previousExtension = extension = 0;
                return;
            }
            previousExtension = extension;
            extension = Mth.clamp(extension + (getBlockState().getValue(PowerDockBlock.DEPLOYED) ? 1 : -1),
                    0, DEPLOY_TICKS);
            return;
        }

        if (getBlockState().getValue(PowerDockBlock.RETRACTING) && server.getGameTime() >= retractionEndTick) {
            showDockState(false, false);
        }
        if (PowerDock.computerCraft && isPinDock() && live()) computer(server).tick();

        DockingSession active = docking;
        if (active != null) {
            if (active.targetDock() == this && !active.update()) active.release();
            return;
        }

        showDockState(false, false);
        if (server.getGameTime() < nextCaptureTick || !ready(server)) return;
        nextCaptureTick = server.getGameTime() + 4;

        var targetBody = (ServerSubLevel) Sable.HELPER.getContaining(this);
        var front = anchor(0.5);
        if (targetBody != null) {
            if (targetBody.isRemoved()) return;
            targetBody.logicalPose().transformPosition(front);
        } else if (Sable.HELPER.isInPlotGrid(server, worldPosition)) {
            return;
        }

        var nearby = new BoundingBox3d(front.x, front.y, front.z, front.x, front.y, front.z)
                .expand(DockingJoint.CAPTURE_RANGE + 0.75);
        DockCandidate closest = null;
        for (var candidate : Sable.HELPER.getAllIntersecting(server, nearby)) {
            if (!(candidate instanceof ServerSubLevel body) || body == targetBody || body.isRemoved()) continue;
            var capture = findCapture(server, targetBody, body);
            if (capture != null && (closest == null || capture.distanceSquared() < closest.distanceSquared())) {
                closest = capture;
            }
        }
        if (closest != null) {
            var ports = closest.ports();
            var joint = closest.joint() != null ? closest.joint() : DockingJoint.start(server, ports);
            if (joint != null) {
                var session = new DockingSession(server, ports, joint);
                ports.targetDock().docking = session;
                ports.approachingDock().docking = session;
            }
        }
    }

    @Nullable
    private DockCandidate findCapture(ServerLevel server, @Nullable ServerSubLevel targetBody, ServerSubLevel body) {
        var joint = DockingJoint.between(targetBody, body);
        if (joint != null && !joint.isValid()) return null;
        boolean searchFromTarget = joint == null || joint.targets(targetBody);
        DockCandidate closest = null;
        for (var actor : body.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof PowerDockBlockEntity other) || other.docking != null
                    || !getBlockState().is(other.getBlockState().getBlock()) || !other.ready(server)) continue;
            var ports = searchFromTarget ? new DockingSession.Ports(this, targetBody, other, body)
                    : new DockingSession.Ports(other, body, this, Objects.requireNonNull(targetBody));
            double distance = joint == null ? DockingJoint.captureDistance(server, ports) : joint.matchDistance(ports);
            if (Double.isFinite(distance) && (closest == null || distance < closest.distanceSquared())) {
                closest = new DockCandidate(ports, joint, distance);
            }
        }
        if (closest != null && joint == null && !DockingJoint.canConnect(targetBody, body)) return null;
        return closest;
    }

    private record DockCandidate(DockingSession.Ports ports, @Nullable DockingJoint joint, double distanceSquared) {}

    @Override
    public void sable$physicsTick(ServerSubLevel body, RigidBodyHandle handle, double timeStep) {
        if (docking != null && docking.approachingDock() == this) docking.joint().advance(docking, body, timeStep);
    }

    @Nullable DockingJoint currentJoint() {
        return docking == null ? null : docking.joint();
    }

    boolean ready(ServerLevel server) {
        return live() && level == server && !getElectricBehaviour().isPaused() && armed(server);
    }

    boolean live() {
        return !isRemoved() && !isChunkUnloaded() && getBlockState().getBlock() instanceof PowerDockBlock;
    }

    boolean isPinDock() {
        return getBlockState().is(PowerDock.PIN_DOCK.get());
    }

    int contactCount() {
        return ((PowerDockBlock) getBlockState().getBlock()).terminalCount();
    }

    DockComputer computer(ServerLevel server) {
        if (computer == null) computer = ComputerCraftIntegration.create(this, server);
        return computer;
    }

    boolean armed(Level level) {
        if (!PowerDock.computerCraft || control == Control.REDSTONE) return level.hasNeighborSignal(worldPosition);
        return control == Control.ARMED;
    }

    boolean aligning() {
        return docking != null && !docking.conducting();
    }

    void setControl(Control next) {
        if (control == next || level == null) return;
        control = next;
        setChanged();
        releaseIfDisabled(level);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket && control != Control.REDSTONE) tag.putByte("DockControl", (byte) control.ordinal());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (!clientPacket) {
            control = switch (tag.getByte("DockControl")) {
                case 1 -> Control.ARMED;
                case 2 -> Control.DISARMED;
                default -> Control.REDSTONE;
            };
        }
    }

    enum Control { REDSTONE, ARMED, DISARMED }

    Direction facing() {
        return getBlockState().getValue(PowerDockBlock.FACING);
    }

    Vector3d anchor(double offset) {
        Direction facing = facing();
        return new Vector3d(worldPosition.getX() + 0.5 + facing.getStepX() * offset,
                worldPosition.getY() + 0.5 + facing.getStepY() * offset,
                worldPosition.getZ() + 0.5 + facing.getStepZ() * offset);
    }

    boolean connected() {
        return docking != null && docking.conducting();
    }

    float armProgress(float partialTick) {
        float phase = Mth.lerp(partialTick, previousExtension, extension) / DEPLOY_TICKS;
        return phase * phase * (3 - 2 * phase);
    }

    boolean drawsMovingArm() {
        BlockState state = getBlockState();
        return state.getValue(PowerDockBlock.DEPLOYED) || state.getValue(PowerDockBlock.RETRACTING);
    }

    void showDockState(boolean deployed, boolean connected) {
        BlockState state = getBlockState();
        if (!live()) return;
        long now = level.getGameTime();
        if (!deployed && state.getValue(PowerDockBlock.DEPLOYED)) {
            // Keep the moving model through the last interpolated client tick.
            retractionEndTick = now + DEPLOY_TICKS + 1;
        }
        BlockState next = state.setValue(PowerDockBlock.DEPLOYED, deployed)
                .setValue(PowerDockBlock.RETRACTING, !deployed && now < retractionEndTick)
                .setValue(PowerDockBlock.CONNECTED, connected);
        if (state == next) return;
        level.setBlock(worldPosition, next, Block.UPDATE_CLIENTS);
        if (state.getValue(PowerDockBlock.CONNECTED) != connected) {
            level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
        }
    }

    void clearDocking(DockingSession session) {
        if (docking == session) {
            docking = null;
            nextCaptureTick = level.getGameTime() + 20;
        }
    }

    void releaseIfDisabled(Level level) {
        if (docking != null && !armed(level)) docking.release();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (docking != null) docking.release();
        if (computer != null) computer.remove();
    }

    @Override
    public void remove() {
        if (docking != null) docking.release();
        if (computer != null) computer.remove();
        super.remove();
    }
}
