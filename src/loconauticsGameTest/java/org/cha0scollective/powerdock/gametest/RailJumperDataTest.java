package org.cha0scollective.powerdock.gametest;

import dan200.computercraft.api.network.Packet;
import dan200.computercraft.api.network.PacketReceiver;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import dan200.computercraft.api.network.wired.WiredNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

final class RailJumperDataTest {
    private final Endpoint front;
    private final Endpoint middle;
    private final Endpoint tail;

    RailJumperDataTest(ServerLevel level, BlockPos frontJumper, BlockPos middleJumper, BlockPos tailJumper) {
        front = new Endpoint(level, frontJumper, Direction.UP);
        middle = new Endpoint(level, middleJumper, Direction.WEST);
        tail = new Endpoint(level, tailJumper, Direction.WEST);
    }

    boolean canReachTail() {
        boolean forward = front.reaches(tail);
        boolean reverse = tail.reaches(front);
        if (forward != reverse) throw new IllegalStateException("The wired bridge only passed packets in one direction");
        return forward;
    }

    boolean canReachMiddle() {
        return front.reaches(middle);
    }

    void close() {
        front.node.removeReceiver(front);
        middle.node.removeReceiver(middle);
        tail.node.removeReceiver(tail);
    }

    private static final class Endpoint implements PacketReceiver {
        private final ServerLevel level;
        private final WiredNode node;
        private Packet received;

        Endpoint(ServerLevel level, BlockPos jumper, Direction dataFace) {
            this.level = level;
            var capability = WiredElementCapability.get();
            for (var face : Direction.values()) {
                if ((level.getCapability(capability, jumper, face) != null) != (face == dataFace)) {
                    throw new IllegalStateException("The Rail Jumper exposes the wired port on the wrong block face");
                }
            }
            this.node = Objects.requireNonNull(level.getCapability(capability, jumper.relative(dataFace), dataFace.getOpposite()),
                    "CC wired modem or cable was not assembled").getNode();
            node.addReceiver(this);
        }

        boolean reaches(Endpoint other) {
            var packet = new Packet(17, 23, "rail jumper test", node.getElement());
            node.transmitSameDimension(packet, Double.POSITIVE_INFINITY);
            return other.received == packet;
        }

        @Override public ServerLevel getLevel() { return level; }
        @Override public Vec3 getPosition() { return node.getElement().getPosition(); }
        @Override public double getRange() { return Double.POSITIVE_INFINITY; }
        @Override public boolean isInterdimensional() { return false; }
        @Override public void receiveSameDimension(Packet packet, double distance) { received = packet; }
        @Override public void receiveDifferentDimension(Packet packet) {}
    }
}
