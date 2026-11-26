package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;

import javax.annotation.Nullable;

public record CatenaryContacts(ServerLevel level, ElectricWire supply, ElectricWire returnWire) {
    @Nullable
    public static CatenaryContacts close(ServerLevel level, Terminals from, Terminals to,
                                         float supplyResistance, float returnResistance) {
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var supply = grid.makeSimpleWire(from.supply, to.supply, supplyResistance);
        if (supply == null) return null;
        ElectricWire returnWire = null;
        try {
            returnWire = grid.makeSimpleWire(from.returnWire, to.returnWire, returnResistance);
        } finally {
            if (returnWire == null) remove(level, supply);
        }
        return returnWire == null ? null : new CatenaryContacts(level, supply, returnWire);
    }

    boolean live() {
        return supply.getNetwork() != null && returnWire.getNetwork() != null;
    }

    public void open() {
        remove(level, supply);
        remove(level, returnWire);
    }

    private static void remove(ServerLevel level, ElectricWire wire) {
        var network = wire.getNetwork();
        if (network == null) return;
        wire.remove();
        GlobalElectricNetworks.getWorldNetworks(level).scheduleIslandDiscovery(network);
    }

    public record Terminals(IWireEndpoint supply, IWireEndpoint returnWire) {
        public static Terminals block(BlockPos pos) {
            return new Terminals(new BlockWireEndpoint(pos, 0), new BlockWireEndpoint(pos, 1));
        }

        public static Terminals virtual(Vec3 position) {
            return new Terminals(new LineTerminal(position), new LineTerminal(position));
        }

        public void retire(ServerLevel level) {
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            if (supply instanceof LineTerminal terminal) grid.nodeHolderRemoved(terminal.node);
            if (returnWire instanceof LineTerminal terminal) grid.nodeHolderRemoved(terminal.node);
        }
    }

    private static final class LineTerminal implements IWireEndpoint {
        private final Vec3 position;
        private final OwnedFloatingNode node = new OwnedFloatingNode(this);

        LineTerminal(Vec3 position) { this.position = position; }

        @Override public OwnedFloatingNode getNode(Level level) { return node; }
        @Override public void joinNetwork(Level level, ElectricalNetwork network) { network.addNode(node); }
        @Override public Vec3 getExactPosition(Level level) { return position; }
        @Override public WireEndpointType type() { return WireEndpointType.IMAGINARY; }
        @Override public void read(CompoundTag tag) { throw new UnsupportedOperationException("Runtime catenary terminal"); }
        @Override public void write(CompoundTag tag) { throw new UnsupportedOperationException("Runtime catenary terminal"); }
    }
}
