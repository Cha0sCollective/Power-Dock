package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;

public final class PowerDockBlockEntity extends ElectricBlockEntity implements BlockEntitySubLevelActor {
    static final double ARM_TRAVEL = 11.0 / 32;
    static final int DEPLOY_TICKS = 6;
    private DockingSession docking;
    private long nextCaptureTick;
    private int extension;
    private int previousExtension;

    PowerDockBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.DOCK_ENTITY.get(), pos, state);
        extension = previousExtension = state.getValue(PowerDockBlock.DEPLOYED) ? DEPLOY_TICKS : 0;
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(PowerDockBlock.TERMINAL_COUNT);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) {
            previousExtension = extension;
            extension = Mth.clamp(extension + (getBlockState().getValue(PowerDockBlock.DEPLOYED) ? 1 : -1),
                    0, DEPLOY_TICKS);
            return;
        }

        DockingSession active = docking;
        if (active != null) {
            if (active.station() == this && !active.update()) active.release();
            return;
        }

        showDockState(false, false);
        // Only the stationary half searches and owns the pair. Plot coordinates never enter the search.
        if (Sable.HELPER.isInPlotGrid(server, worldPosition) || server.getGameTime() < nextCaptureTick
                || !ready(server)) return;
        nextCaptureTick = server.getGameTime() + 4;

        var front = worldPosition.getCenter().relative(facing(), 0.5);
        var nearby = new BoundingBox3d(front, front).expand(DockingSession.CAPTURE_RANGE + 0.75);
        DockingSession.Capture closest = null;
        for (var candidate : Sable.HELPER.getAllIntersecting(server, nearby)) {
            if (!(candidate instanceof ServerSubLevel body) || body.isRemoved()) continue;
            var capture = findCapture(server, body);
            if (capture != null && (closest == null || capture.distanceSquared() < closest.distanceSquared())) {
                closest = capture;
            }
        }
        if (closest != null) {
            DockingSession session = closest.start(server, this);
            docking = session;
            closest.ship().docking = session;
        }
    }

    private DockingSession.Capture findCapture(ServerLevel server, ServerSubLevel body) {
        DockingSession.Capture closest = null;
        for (var actor : body.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof PowerDockBlockEntity ship)) continue;
            // Two rigid joints on one ship can fight each other.
            if (ship.docking != null) return null;
            if (!ship.ready(server)) continue;
            var capture = DockingSession.findCapture(server, this, ship, body);
            if (capture != null && (closest == null || capture.distanceSquared() < closest.distanceSquared())) {
                closest = capture;
            }
        }
        return closest;
    }

    @Override
    public void sable$physicsTick(ServerSubLevel body, RigidBodyHandle handle, double timeStep) {
        if (docking != null && docking.ship() == this) docking.advance(body, timeStep);
    }

    boolean ready(ServerLevel server) {
        return live() && level == server && !getElectricBehaviour().isPaused() && server.hasNeighborSignal(worldPosition);
    }

    private boolean live() {
        return !isRemoved() && !isChunkUnloaded() && getBlockState().is(PowerDock.DOCK.get());
    }

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

    void showDockState(boolean deployed, boolean connected) {
        BlockState state = getBlockState();
        if (!live()) return;
        BlockState next = state.setValue(PowerDockBlock.DEPLOYED, deployed)
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
        if (docking != null && !level.hasNeighborSignal(worldPosition)) docking.release();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (docking != null) docking.release();
    }

    @Override
    public void remove() {
        if (docking != null) docking.release();
        super.remove();
    }
}
