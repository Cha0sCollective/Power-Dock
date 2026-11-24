package org.cha0scollective.powerdock.gametest;

import com.lycoris.loconautics.trainphysics.SableTrain;
import com.lycoris.loconautics.trainphysics.glue.RailGlue;
import com.lycoris.loconautics.trainphysics.spawn.SableTrainSpawner;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.List;

@GameTestHolder("powerdock_loconautics_tests")
@PrefixGameTestTemplate(false)
public final class LoconauticsDockGameTest {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void roofDockChargesAcrossVerticalOffset(GameTestHelper test) {
        verify(test, 0, Direction.UP, new Vec3(0, 0.375, 0), false, false);
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void sidePinDockSignalsAcrossOffsetAndReleases(GameTestHelper test) {
        verify(test, 2, Direction.NORTH, new Vec3(0.1875, 0.375, 0.499), true, false);
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void airshipAndCarriageTransferPower(GameTestHelper test) {
        verify(test, 4, Direction.NORTH, new Vec3(0.125, 0.25, 0.125), false, true);
    }

    private static void verify(GameTestHelper test, int chunk, Direction facing, Vec3 offset,
                               boolean pinDock, boolean shipToTrain) {
        var level = test.getLevel();
        // Rapier needs the fixture close to the origin for sub-block offsets.
        var bogey = new BlockPos(23, -55, chunk * 16 + 7);
        var dock = bogey.above();
        var station = dock.relative(facing, 2);
        var stationPower = station.relative(facing);
        var blocker = station.relative(facing.getOpposite());
        var dockState = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                "powerdock", pinDock ? "pin_dock" : "power_dock")).defaultBlockState();
        for (int x = 0; x < 3; x++) level.setChunkForced(x, chunk, true);
        for (int x = -19; x <= 19; x++) {
            var rail = bogey.below().east(x);
            level.setBlockAndUpdate(rail.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(rail, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
        }
        level.setBlockAndUpdate(bogey, AllBlocks.SMALL_BOGEY.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(dock, dockState.setValue(BlockStateProperties.FACING, facing));
        level.setBlockAndUpdate(dock.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(Vec3.atLowerCornerOf(bogey),
                Vec3.atLowerCornerOf(dock.east(2).south().above()))));
        level.setBlockAndUpdate(station, dockState.setValue(BlockStateProperties.FACING, facing.getOpposite()));

        var counterpart = new BlockPos[]{station};
        var carriage = new SableTrain[1];
        var trainBody = new ServerSubLevel[1];
        var parked = new Vec3[1];
        var load = new ElectricWire[1];
        var ship = new ServerSubLevel[1];
        var shipParked = new Vec3[1];
        test.runAtTickTime(12, () -> {
            TrackPropagator.onRailAdded(level, bogey.below(), level.getBlockState(bogey.below()));
            var train = SableTrainSpawner.assembleAt(level, bogey, new Vec3(1, 0, 0), null, "Power Dock test");
            test.assertTrue(train != null && !train.car().bogeys().isEmpty(), "Loconautics could not seat the carriage");
            train.poseTweak().setOffset(offset.x, offset.y, offset.z);
            train.poseTweak().snap();
            carriage[0] = train;
            trainBody[0] = (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(train.car().subLevelId());
            if (shipToTrain) {
                level.setBlockAndUpdate(stationPower, Blocks.STONE.defaultBlockState());
                for (var floor : List.of(station.below(), stationPower.below())) {
                    level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                }
                ship[0] = SubLevelAssemblyHelper.assembleBlocks(level, station, List.of(station, stationPower),
                        new BoundingBox3i(station, stationPower));
                counterpart[0] = ship[0].getPlot().getCenterBlock();
            }
        });
        test.runAtTickTime(58, () -> {
            assertOnRails(test, trainBody[0], "before arming");
            parked[0] = dockCenter(trainBody[0]);
            if (shipToTrain) shipParked[0] = ship[0].logicalPose().transformPosition(counterpart[0].getCenter());
            if (pinDock) level.setBlockAndUpdate(blocker, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(counterpart[0].relative(facing), Blocks.REDSTONE_BLOCK.defaultBlockState());
            load[0] = feed(level, counterpart[0], trainBody[0].getPlot().getCenterBlock().above(), pinDock);
        });
        test.runAtTickTime(76, () -> {
            if (pinDock) {
                test.assertTrue(!connected(level, counterpart[0]) && Math.abs(load[0].potentialDifference()) < 0.01,
                        "The Pin Dock connected through a solid block");
                level.removeBlock(blocker, false);
            }
        });
        test.runAtTickTime(101, () -> {
            test.assertTrue(connected(level, counterpart[0]) && Math.abs(load[0].potentialDifference()) > 20,
                    "The offset contacts failed to transfer power");
            assertOnRails(test, trainBody[0], "while connected");
            test.assertTrue(parked[0].distanceTo(dockCenter(trainBody[0])) < 0.06, "Docking displaced the parked carriage");
            if (shipToTrain) {
                var center = ship[0].logicalPose().transformPosition(counterpart[0].getCenter());
                test.assertTrue(center.distanceTo(shipParked[0]) < 0.08, "Train docking pulled the airship");
            }
            // Shift the rail-guided carriage along the track while leaving both redstone inputs on.
            carriage[0].poseTweak().setOffset(offset.x + 1.5, offset.y, offset.z);
        });
        test.runAtTickTime(131, () -> {
            assertOnRails(test, trainBody[0], "after departure");
            test.assertTrue(Math.abs(parked[0].x - dockCenter(trainBody[0]).x) > 1,
                    "The carriage could not move away from the dock");
            test.assertTrue(level.hasNeighborSignal(counterpart[0]) && !connected(level, counterpart[0])
                            && Math.abs(load[0].potentialDifference()) < 0.01,
                    "Moving out of reach did not open the still-armed contacts");
            for (int x = 0; x < 3; x++) level.setChunkForced(x, chunk, false);
            test.succeed();
        });
    }

    private static Vec3 dockCenter(ServerSubLevel body) {
        return body.logicalPose().transformPosition(body.getPlot().getCenterBlock().above().getCenter());
    }

    private static void assertOnRails(GameTestHelper test, ServerSubLevel body, String phase) {
        var rail = RailGlue.CONSTRAINTS.get(body.getUniqueId());
        test.assertTrue(rail != null && rail.isValid(), "Loconautics lost its rail constraint " + phase);
    }

    private static boolean connected(ServerLevel level, BlockPos dock) {
        return level.getBlockState(dock).getAnalogOutputSignal(level, dock) == 15;
    }

    private static ElectricWire feed(ServerLevel level, BlockPos station, BlockPos carriage, boolean pinDock) {
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        int signal = pinDock ? 1 : 0;
        int common = pinDock ? 0 : 1;
        var positive = new BlockWireEndpoint(station, signal);
        var negative = new BlockWireEndpoint(station, common);
        var network = grid.prepareForConnection(positive, negative);
        var source = new CurrentSourceWire(positive.getNode(level), negative.getNode(level), 1);
        source.setCurrent(24);
        network.addWire(source);
        return grid.makeSimpleWire(new BlockWireEndpoint(carriage, signal), new BlockWireEndpoint(carriage, common), 20);
    }
}
