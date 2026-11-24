package org.cha0scollective.powerdock;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.network.wired.WiredElement;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import dan200.computercraft.api.network.wired.WiredNode;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

final class ComputerCraftIntegration {
    private ComputerCraftIntegration() {}

    static void register(IEventBus bus) {
        bus.addListener(ComputerCraftIntegration::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), PowerDock.DOCK_ENTITY.get(), (dock, side) ->
                dock.live() && dock.getLevel() instanceof ServerLevel level ? (Peripheral) dock.computer(level) : null);
        event.registerBlockEntity(WiredElementCapability.get(), PowerDock.DOCK_ENTITY.get(), (dock, side) ->
                dock.live() && dock.isPinDock() && side == dock.facing().getOpposite()
                        && dock.getLevel() instanceof ServerLevel level ? ((Peripheral) dock.computer(level)).port : null);
        event.registerBlockEntity(WiredElementCapability.get(), PowerDock.JUMPER_ENTITY.get(), (jumper, side) ->
                jumper.live() && side == jumper.dataFace() && jumper.getLevel() instanceof ServerLevel level
                        ? (WiredPort) jumper.computer(level) : null);
    }

    static DockComputer create(PowerDockBlockEntity dock, ServerLevel level) {
        return new Peripheral(dock, level);
    }

    static DockComputer create(RailJumperBlockEntity jumper, ServerLevel level) {
        return new WiredPort(jumper, level, jumper::dataFace);
    }

    private static final class WiredPort implements WiredElement, DockComputer {
        private final BlockEntity host;
        private final ServerLevel level;
        private final String senderId;
        private final WiredNode node;
        private final Supplier<Direction> connectorSide;
        private volatile Vec3 position;
        @Nullable private WiredNode localConnection;
        private Direction side;

        WiredPort(BlockEntity host, ServerLevel level, Supplier<Direction> connectorSide) {
            this.host = host;
            this.level = level;
            this.connectorSide = connectorSide;
            this.senderId = "powerdock:" + level.dimension().location() + "/" + host.getBlockPos().asLong();
            this.position = host.getBlockPos().getCenter();
            this.side = connectorSide.get();
            this.node = ComputerCraftAPI.createWiredNodeForElement(this);
        }

        @Override
        public void tick() {
            var body = (ServerSubLevel) Sable.HELPER.getContaining(host);
            var center = host.getBlockPos().getCenter();
            // Wired messages can be sent off-thread. Publish a current world-space position on the server thread.
            position = body == null ? center : body.logicalPose().transformPosition(center);
            var currentSide = connectorSide.get();
            if (side != currentSide) {
                side = currentSide;
                level.invalidateCapabilities(host.getBlockPos());
            }
            var neighbor = host.getBlockPos().relative(side);
            WiredElement cable = null;
            if (level.hasChunkAt(neighbor) && Sable.HELPER.getContaining(level, neighbor) == body) {
                var block = level.getBlockState(neighbor).getBlock();
                if (!(block instanceof PowerDockBlock) && !(block instanceof RailJumperBlock)) {
                    cable = level.getCapability(WiredElementCapability.get(), neighbor, side.getOpposite());
                }
            }
            var next = cable == null ? null : cable.getNode();
            if (next == localConnection) return;
            if (localConnection != null) node.disconnectFrom(localConnection);
            localConnection = next;
            if (next != null) node.connectTo(next);
        }

        @Override
        public void connect(DockComputer peer) {
            if (peer instanceof WiredPort other) node.connectTo(other.node);
        }

        @Override
        public void disconnect(DockComputer peer) {
            if (peer instanceof WiredPort other) node.disconnectFrom(other.node);
        }

        @Override
        public void remove() {
            node.remove();
            localConnection = null;
        }

        @Override
        public WiredNode getNode() {
            return node;
        }

        @Override
        public ServerLevel getLevel() {
            return level;
        }

        @Override
        public Vec3 getPosition() {
            return position;
        }

        @Override
        public String getSenderID() {
            return senderId;
        }
    }

    public static final class Peripheral implements IPeripheral, DockComputer {
        private final PowerDockBlockEntity dock;
        private final ServerLevel level;
        @Nullable private final WiredPort port;

        Peripheral(PowerDockBlockEntity dock, ServerLevel level) {
            this.dock = dock;
            this.level = level;
            this.port = dock.isPinDock() ? new WiredPort(dock, level, () -> dock.facing().getOpposite()) : null;
        }

        @Override
        public void tick() {
            if (port != null) port.tick();
        }

        @Override
        public void connect(DockComputer peer) {
            if (port != null && peer instanceof Peripheral other && other.port != null) port.connect(other.port);
        }

        @Override
        public void disconnect(DockComputer peer) {
            if (port != null && peer instanceof Peripheral other && other.port != null) port.disconnect(other.port);
        }

        @Override
        public void remove() {
            if (port != null) port.remove();
        }

        @Override
        public String getType() {
            return "power_dock";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            return other == this;
        }

        @LuaFunction(mainThread = true)
        public final String getState() throws LuaException {
            checkAvailable();
            if (dock.connected()) return "docked";
            if (dock.aligning()) return "aligning";
            return dock.armed(level) ? "armed" : "disarmed";
        }

        @LuaFunction(mainThread = true)
        public final int getContactCount() throws LuaException {
            checkAvailable();
            return dock.contactCount();
        }

        @LuaFunction(mainThread = true)
        public final void setArmed(boolean armed) throws LuaException {
            checkAvailable();
            dock.setControl(armed ? PowerDockBlockEntity.Control.ARMED : PowerDockBlockEntity.Control.DISARMED);
        }

        @LuaFunction(mainThread = true)
        public final void release() throws LuaException {
            setArmed(false);
        }

        @LuaFunction(mainThread = true)
        public final void useRedstone() throws LuaException {
            checkAvailable();
            dock.setControl(PowerDockBlockEntity.Control.REDSTONE);
        }

        @LuaFunction(mainThread = true)
        public final MethodResult getVoltage(int pin, Optional<Integer> reference) throws LuaException {
            checkAvailable();
            var pos = dock.getBlockPos();
            var signal = new BlockWireEndpoint(pos, terminal(pin)).getNode(level);
            var common = new BlockWireEndpoint(pos, terminal(reference.orElse(dock.contactCount()))).getNode(level);
            if (signal == null || common == null || signal.getNetwork() == null || signal.getNetwork() != common.getNetwork()) {
                return MethodResult.of();
            }
            return MethodResult.of(signal.getVoltage() - common.getVoltage());
        }

        private int terminal(int pin) throws LuaException {
            if (pin < 1 || pin > dock.contactCount()) throw new LuaException("Invalid dock pin " + pin);
            if (!dock.isPinDock()) return pin - 1;
            return pin == PowerDockBlock.COMMON_PIN ? 0 : pin;
        }

        private void checkAvailable() throws LuaException {
            if (!dock.live() || level.getBlockEntity(dock.getBlockPos()) != dock) {
                throw new LuaException("The Power Dock is unavailable");
            }
        }
    }
}
