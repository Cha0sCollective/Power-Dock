package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import javax.annotation.Nullable;

record CatenaryContacts(ServerLevel level, ElectricWire supply, ElectricWire returnWire) {
    @Nullable
    static CatenaryContacts close(ServerLevel level, BlockPos from, BlockPos to, float resistance) {
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var supply = grid.makeSimpleWire(new BlockWireEndpoint(from, 0), new BlockWireEndpoint(to, 0), resistance);
        if (supply == null) return null;
        ElectricWire returnWire = null;
        try {
            returnWire = grid.makeSimpleWire(new BlockWireEndpoint(from, 1), new BlockWireEndpoint(to, 1), resistance);
        } finally {
            if (returnWire == null) remove(level, supply);
        }
        return returnWire == null ? null : new CatenaryContacts(level, supply, returnWire);
    }

    boolean live() {
        return supply.getNetwork() != null && returnWire.getNetwork() != null;
    }

    void open() {
        remove(level, supply);
        remove(level, returnWire);
    }

    private static void remove(ServerLevel level, ElectricWire wire) {
        var network = wire.getNetwork();
        wire.remove();
        if (network != null) GlobalElectricNetworks.getWorldNetworks(level).scheduleIslandDiscovery(network);
    }
}
