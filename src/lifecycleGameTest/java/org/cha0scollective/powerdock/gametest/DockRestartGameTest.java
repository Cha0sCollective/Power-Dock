package org.cha0scollective.powerdock.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@GameTestHolder("powerdock_lifecycle_tests")
@PrefixGameTestTemplate(false)
public final class DockRestartGameTest {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void restoresDockInFreshProcess(GameTestHelper test) {
        var level = test.getLevel();
        var record = level.getServer().getWorldPath(LevelResource.ROOT).resolve("power-dock-restart.properties");
        String phase = System.getProperty("powerdock.test.lifecyclePhase");
        boolean restoring = "restore".equals(phase);
        var fixture = restoring ? Fixture.load(record) : seed(level, "save-abrupt".equals(phase));
        var measured = new ElectricWire[1];

        test.startSequence().thenWaitUntil(() -> assertSeated(test, fixture))
                .thenExecute(() -> measured[0] = powerLoad(level, fixture))
                .thenIdle(8).thenExecute(() -> {
                    assertSeated(test, fixture);
                    var load = measured[0];
                    test.assertTrue(load != null && Math.abs(load.potentialDifference()) > 10,
                            "The reconnected circuit did not deliver power");
                    if (restoring) {
                        System.out.printf("POWER_DOCK_RESTART_RESTORED pairs=%d airborne=%s volts=%.2f%n",
                                fixture.offsets().size(), fixture.airborne(), Math.abs(load.potentialDifference()));
                    } else {
                        fixture.save(record);
                        if (fixture.airborne()) {
                            var body = body(level, fixture.movingId());
                            var velocity = SubLevelContainer.getContainer(level).physicsSystem().getPipeline()
                                    .getLinearVelocity(body, new org.joml.Vector3d());
                            test.assertTrue(velocity.y < -2, "The checkpoint must contain a moving, airborne hull");
                            level.getServer().saveEverything(true, true, true);
                            System.out.println("POWER_DOCK_RESTART_ABRUPT_CHECKPOINT_SAVED");
                            System.out.flush();
                            // The runner expects this exit code only after verifying the flushed fixture checkpoint.
                            Runtime.getRuntime().halt(86);
                        }
                        System.out.println("POWER_DOCK_RESTART_CLEAN_CHECKPOINT_READY");
                    }
                }).thenSucceed();
    }

    private static Fixture seed(ServerLevel level, boolean airborne) {
        var first = airborne ? new BlockPos(36, 248, 4) : new BlockPos(3, -55, 4);
        var second = first.east(3);
        var offsets = airborne ? List.of(0, 3) : List.of(0);
        level.setChunkForced(first.getX() >> 4, first.getZ() >> 4, true);
        if (!airborne) {
            for (var pos : BlockPos.betweenClosed(first.west(2).below().north(), second.east(2).below().south())) {
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            }
        }
        var leftBlocks = placePorts(level, first, Direction.EAST, offsets, airborne);
        var rightBlocks = placePorts(level, second, Direction.WEST, offsets, airborne);
        int span = offsets.getLast();
        var left = airborne ? SubLevelAssemblyHelper.assembleBlocks(level, first, leftBlocks,
                new BoundingBox3i(first.west(), first.south(span))) : null;
        var right = SubLevelAssemblyHelper.assembleBlocks(level, second, rightBlocks,
                new BoundingBox3i(second, second.east().south(span)));
        return new Fixture(first, left == null ? null : left.getUniqueId(), right.getUniqueId());
    }

    private static List<BlockPos> placePorts(ServerLevel level, BlockPos center, Direction facing,
                                             List<Integer> offsets, boolean pinDock) {
        var id = ResourceLocation.fromNamespaceAndPath("powerdock", pinDock ? "pin_dock" : "power_dock");
        var state = BuiltInRegistries.BLOCK.get(id).defaultBlockState().setValue(BlockStateProperties.FACING, facing);
        var blocks = new ArrayList<BlockPos>();
        for (int offset = 0; offset <= offsets.getLast(); offset++) {
            var port = center.south(offset);
            var back = port.relative(facing.getOpposite());
            if (offsets.contains(offset)) {
                level.setBlockAndUpdate(port, state);
                level.setBlockAndUpdate(back, Blocks.REDSTONE_BLOCK.defaultBlockState());
                blocks.add(port);
            } else {
                level.setBlockAndUpdate(back, Blocks.STONE.defaultBlockState());
            }
            blocks.add(back);
        }
        return blocks;
    }

    private static void assertSeated(GameTestHelper test, Fixture fixture) {
        var level = test.getLevel();
        var moving = body(level, fixture.movingId());
        var carrier = fixture.airborne() ? body(level, fixture.carrierId()) : null;
        test.assertTrue(moving != null && (!fixture.airborne() || carrier != null), "Waiting for saved Sable hulls");
        var first = carrier == null ? fixture.station() : carrier.getPlot().getCenterBlock();
        var second = moving.getPlot().getCenterBlock();
        if (fixture.airborne()) {
            test.assertTrue(moving.logicalPose().position().y() > 160 && carrier.logicalPose().position().y() > 160,
                    "The unsupported hulls left the flight test area");
        }
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        for (int offset : fixture.offsets()) {
            var a = first.south(offset);
            var b = second.south(offset);
            test.assertTrue(level.getBlockState(a).getAnalogOutputSignal(level, a) == 15
                            && level.getBlockState(b).getAnalogOutputSignal(level, b) == 15,
                    "Waiting for both dock heads at offset " + offset);
            for (int pin = 0; pin < (fixture.airborne() ? 9 : 2); pin++) {
                var left = new BlockWireEndpoint(a, pin).getNode(level);
                var right = new BlockWireEndpoint(b, pin).getNode(level);
                test.assertTrue(grid.globalGraph.getWires(left, right).size() == 1,
                        "Expected one live contact per terminal after seating, terminal " + pin);
            }
        }
    }

    private static ElectricWire powerLoad(ServerLevel level, Fixture fixture) {
        var first = fixture.airborne() ? body(level, fixture.carrierId()).getPlot().getCenterBlock() : fixture.station();
        var second = body(level, fixture.movingId()).getPlot().getCenterBlock();
        int signal = fixture.airborne() ? 1 : 0;
        int common = fixture.airborne() ? 0 : 1;
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var positive = new BlockWireEndpoint(first, signal);
        var negative = new BlockWireEndpoint(first, common);
        var circuit = grid.prepareForConnection(positive, negative);
        var supply = new CurrentSourceWire(positive.getNode(level), negative.getNode(level), 1);
        supply.setCurrent(12);
        circuit.addWire(supply);
        return grid.makeSimpleWire(new BlockWireEndpoint(second, signal), new BlockWireEndpoint(second, common), 20);
    }

    private static ServerSubLevel body(ServerLevel level, UUID id) {
        return (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(id);
    }

    private record Fixture(BlockPos station, UUID carrierId, UUID movingId) {
        boolean airborne() { return carrierId != null; }

        List<Integer> offsets() { return airborne() ? List.of(0, 3) : List.of(0); }

        void save(Path path) {
            var data = new Properties();
            data.setProperty("station", Long.toString(station.asLong()));
            data.setProperty("craft", movingId.toString());
            if (carrierId != null) data.setProperty("carrier", carrierId.toString());
            try (var stream = Files.newOutputStream(path)) {
                data.store(stream, "Power Dock restart test fixture");
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static Fixture load(Path path) {
            var data = new Properties();
            try (var stream = Files.newInputStream(path)) {
                data.load(stream);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            var carrier = data.getProperty("carrier");
            return new Fixture(BlockPos.of(Long.parseLong(data.getProperty("station"))),
                    carrier == null ? null : UUID.fromString(carrier), UUID.fromString(data.getProperty("craft")));
        }
    }
}
