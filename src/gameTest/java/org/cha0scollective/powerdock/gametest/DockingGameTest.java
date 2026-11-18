package org.cha0scollective.powerdock.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.List;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class DockingGameTest {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void alignsConductsAndReleases(GameTestHelper test) {
        var level = test.getLevel();
        var world = GlobalElectricNetworks.getWorldNetworks(level);
        // GameTest scatters its templates millions of blocks from spawn, where Rapier loses
        // sub-block precision. Keep this isolated physics fixture in the spawn chunk.
        level.setChunkForced(0, 0, true);
        BlockPos stationPos = new BlockPos(3, -55, 3);
        BlockPos stationPower = stationPos.west();
        BlockPos launchPos = stationPos.east(3);
        BlockPos launchPower = launchPos.east();
        for (var floor : BlockPos.betweenClosed(stationPos.west().below().north(),
                launchPower.east().below().south())) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        var dock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("powerdock", "power_dock"))
                .defaultBlockState();
        verifyTerminalLabels(test, dock);
        level.setBlockAndUpdate(stationPos, dock.setValue(BlockStateProperties.FACING, Direction.EAST));
        level.setBlockAndUpdate(launchPos, dock.setValue(BlockStateProperties.FACING, Direction.EAST));
        level.setBlockAndUpdate(launchPower, Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(stationPower, Blocks.REDSTONE_BLOCK.defaultBlockState());

        var body = SubLevelAssemblyHelper.assembleBlocks(level, launchPos, List.of(launchPos, launchPower),
                new BoundingBox3i(launchPos, launchPower));
        BlockPos shipPos = body.getPlot().getCenterBlock();
        var stationPositive = new BlockWireEndpoint(stationPos, 0);
        var stationReturn = new BlockWireEndpoint(stationPos, 1);
        var shipPositive = new BlockWireEndpoint(shipPos, 0);
        var shipReturn = new BlockWireEndpoint(shipPos, 1);
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();

        test.runAtTickTime(8, () -> {
            test.assertTrue(!deployed(level, stationPos) && !deployed(level, shipPos),
                    "Arms deployed while facing the same way");
            double launchX = body.logicalPose().transformPosition(shipPos.getCenter()).x;
            test.assertTrue(Math.abs(launchX - launchPos.getCenter().x) < 0.15,
                    "The backwards ship moved unexpectedly (x offset " + (launchX - launchPos.getCenter().x) + ")");
            level.setBlockAndUpdate(shipPos, dock.setValue(BlockStateProperties.FACING, Direction.WEST));
            pipeline.resetVelocity(body);

            var circuit = world.prepareForConnection(stationPositive, stationReturn);
            var source = new CurrentSourceWire(stationPositive.getNode(level), stationReturn.getNode(level), 1);
            source.setCurrent(12);
            circuit.addWire(source);
            var load = world.makeSimpleWire(shipPositive, shipReturn, 20);

            test.runAfterDelay(3, () ->
                    test.assertTrue(!deployed(level, stationPos) && !deployed(level, shipPos),
                            "Arms deployed across a gap larger than one block"));
            test.runAfterDelay(14, () -> {
                test.assertTrue(deployed(level, stationPos) && deployed(level, shipPos),
                        "Both arms did not deploy as the gap closed");
                test.assertTrue(!connected(level, stationPos) && Math.abs(load.potentialDifference()) < 0.01,
                        "The ship received power before the arms settled");
            });
            test.runAfterDelay(52, () -> {
                test.assertTrue(connected(level, stationPos) && connected(level, shipPos), "The aligned ports did not close");
                double dockedX = body.logicalPose().transformPosition(shipPos.getCenter()).x;
                test.assertTrue(launchX - dockedX > 1.2, "The ship was not pulled in");
                test.assertTrue(Math.abs(dockedX - stationPos.getCenter().x - 1.6875) < 0.01,
                        "The two housings did not settle close enough for the contact faces to meet");
                test.assertTrue(Math.abs(load.potentialDifference()) > 10, "The ship load received no voltage");
                test.assertTrue(world.globalGraph.getWires(stationPositive.getNode(level), shipPositive.getNode(level)).size() == 1
                                && world.globalGraph.getWires(stationReturn.getNode(level), shipReturn.getNode(level)).size() == 1,
                        "Expected one native electrical contact per pole");
                level.setBlockAndUpdate(shipPos.east(), Blocks.AIR.defaultBlockState());
                test.assertTrue(!connected(level, stationPos) && !connected(level, shipPos),
                        "Loss of ship redstone did not open the ports");
                test.assertTrue(!deployed(level, stationPos) && !deployed(level, shipPos),
                        "The arms did not start retracting on release");
                test.assertTrue(world.globalGraph.getWires(stationPositive.getNode(level), shipPositive.getNode(level)).isEmpty()
                                && world.globalGraph.getWires(stationReturn.getNode(level), shipReturn.getNode(level)).isEmpty(),
                        "An electrical contact survived undocking");
            });
            test.runAfterDelay(59, () -> {
                test.assertTrue(Math.abs(load.potentialDifference()) < 0.01, "The released ship was still powered");
                source.remove();
                load.remove();
                var shipCircuit = world.prepareForConnection(shipPositive, shipReturn);
                var shipSource = new CurrentSourceWire(shipPositive.getNode(level), shipReturn.getNode(level), 1);
                shipSource.setCurrent(12);
                shipCircuit.addWire(shipSource);
                var stationLoad = world.makeSimpleWire(stationPositive, stationReturn, 20);
                level.setBlockAndUpdate(shipPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());

                test.runAfterDelay(53, () -> {
                    test.assertTrue(connected(level, stationPos) && connected(level, shipPos), "The ports could not reconnect");
                    test.assertTrue(Math.abs(stationLoad.potentialDifference()) > 10, "The ship did not power the station");
                    shipSource.remove();
                    stationLoad.remove();
                    level.removeBlock(stationPos, false);
                    test.assertTrue(!connected(level, shipPos), "Breaking the station left the ship connected");
                    level.setChunkForced(0, 0, false);
                    test.succeed();
                });
            });
        });
    }

    private static boolean connected(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getAnalogOutputSignal(level, pos) == 15;
    }

    private static void verifyTerminalLabels(GameTestHelper test, BlockState dock) {
        var electric = (IElectric) dock.getBlock();
        for (Direction facing : Direction.values()) {
            var state = dock.setValue(BlockStateProperties.FACING, facing);
            var positive = electric.terminal(state, 0);
            var negative = electric.terminal(state, 1);
            test.assertTrue(positive instanceof IDecoratedTerminal label
                            && label.getName().equals(IDecoratedTerminal.POSITIVE)
                            && label.getColor() == IDecoratedTerminal.RED
                            && electric.terminalIndexAt(state, positive.getOrigin()) == 0,
                    "Positive terminal did not use the native Power Grid highlight while facing " + facing);
            test.assertTrue(negative instanceof IDecoratedTerminal label
                            && label.getName().equals(IDecoratedTerminal.NEGATIVE)
                            && label.getColor() == IDecoratedTerminal.BLUE
                            && electric.terminalIndexAt(state, negative.getOrigin()) == 1,
                    "Negative terminal did not use the native Power Grid highlight while facing " + facing);
        }
    }

    private static boolean deployed(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getProperties().stream().anyMatch(property ->
                property.getName().equals("deployed") && Boolean.TRUE.equals(state.getValue(property)));
    }
}
