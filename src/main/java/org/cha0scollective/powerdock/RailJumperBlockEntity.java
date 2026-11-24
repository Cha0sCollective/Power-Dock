package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import javax.annotation.Nullable;

public final class RailJumperBlockEntity extends ElectricBlockEntity {
    @Nullable private Link link;
    @Nullable private BlockPos renderPartner;
    @Nullable private DockComputer computer;
    private long nextSearchTick;

    RailJumperBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.JUMPER_ENTITY.get(), pos, state);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) return;
        if (computer != null) computer.tick();
        if (link != null) {
            if (link.first == this && !link.valid()) link.open();
            return;
        }
        if (getBlockState().getValue(RailJumperBlock.CONNECTED)) showConnection(null, false);
        if (server.getGameTime() < nextSearchTick || !ready(server)) return;
        nextSearchTick = server.getGameTime() + 4;
        RailJumperCoupling.tryConnect(server, this);
    }

    static void connect(ServerLevel server, RailJumperBlockEntity first, RailJumperBlockEntity second,
                        RailJumperCoupling.Knuckle firstKnuckle, RailJumperCoupling.Knuckle secondKnuckle) {
        Link.close(server, first, second, firstKnuckle, secondKnuckle);
    }

    boolean ready(ServerLevel server) {
        return level == server && live() && !getElectricBehaviour().isPaused();
    }

    boolean live() {
        return !isRemoved() && !isChunkUnloaded() && getBlockState().getBlock() instanceof RailJumperBlock;
    }

    DockComputer computer(ServerLevel server) {
        if (computer == null) computer = ComputerCraftIntegration.create(this, server);
        return computer;
    }

    boolean connected() {
        return link != null;
    }

    Direction facing() {
        return getBlockState().getValue(RailJumperBlock.FACING);
    }

    Direction dataFace() {
        var state = getBlockState();
        return ((RailJumperBlock) state.getBlock()).dataFace(state);
    }

    @Nullable BlockPos renderPartner() {
        return renderPartner;
    }

    private void showConnection(@Nullable BlockPos partner, boolean connected) {
        renderPartner = partner;
        if (!live()) return;
        var state = getBlockState();
        if (state.getValue(RailJumperBlock.CONNECTED) != connected) {
            level.setBlock(worldPosition, state.setValue(RailJumperBlock.CONNECTED, connected), Block.UPDATE_CLIENTS);
            level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
        }
        sendData();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket && renderPartner != null) tag.putLong("JumperPartner", renderPartner.asLong());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) renderPartner = tag.contains("JumperPartner") ? BlockPos.of(tag.getLong("JumperPartner")) : null;
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (link != null) link.open();
        removeComputer();
    }

    @Override
    public void remove() {
        if (link != null) link.open();
        removeComputer();
        super.remove();
    }

    private void removeComputer() {
        if (computer != null) computer.remove();
        computer = null;
    }

    private record Link(ServerLevel level, RailJumperBlockEntity first, RailJumperBlockEntity second,
                        RailJumperCoupling.Knuckle firstKnuckle, RailJumperCoupling.Knuckle secondKnuckle,
                        ElectricWire positive, ElectricWire negative) {
        private static final float CONDUCTOR_RESISTANCE = 0.025f;

        static void close(ServerLevel level, RailJumperBlockEntity first, RailJumperBlockEntity second,
                          RailJumperCoupling.Knuckle firstKnuckle, RailJumperCoupling.Knuckle secondKnuckle) {
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            var positive = grid.makeSimpleWire(new BlockWireEndpoint(first.worldPosition, 0),
                    new BlockWireEndpoint(second.worldPosition, 0), CONDUCTOR_RESISTANCE);
            if (positive == null) return;
            ElectricWire negative = null;
            try {
                negative = grid.makeSimpleWire(new BlockWireEndpoint(first.worldPosition, 1),
                        new BlockWireEndpoint(second.worldPosition, 1), CONDUCTOR_RESISTANCE);
            } finally {
                if (negative == null) remove(level, positive);
            }
            if (negative == null) {
                return;
            }

            var link = new Link(level, first, second, firstKnuckle, secondKnuckle, positive, negative);
            first.link = second.link = link;
            if (PowerDock.computerCraft) first.computer(level).connect(second.computer(level));
            first.showConnection(second.worldPosition, true);
            if (second.link == link) second.showConnection(null, true);
        }

        boolean valid() {
            return positive.getNetwork() != null && negative.getNetwork() != null
                    && firstKnuckle.owns(level, first) && secondKnuckle.owns(level, second)
                    && firstKnuckle.linksTo(level, secondKnuckle) && secondKnuckle.linksTo(level, firstKnuckle);
        }

        void open() {
            first.link = second.link = null;
            if (first.computer != null && second.computer != null) first.computer.disconnect(second.computer);
            remove(level, positive);
            remove(level, negative);
            first.showConnection(null, false);
            second.showConnection(null, false);
        }

        private static void remove(ServerLevel level, ElectricWire wire) {
            var network = wire.getNetwork();
            wire.remove();
            if (network != null) GlobalElectricNetworks.getWorldNetworks(level).scheduleIslandDiscovery(network);
        }
    }
}
