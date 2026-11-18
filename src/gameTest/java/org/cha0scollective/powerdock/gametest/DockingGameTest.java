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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.ArrayList;
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

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void docksTwoMovingSublevels(GameTestHelper test) {
        var level = test.getLevel();
        var world = GlobalElectricNetworks.getWorldNetworks(level);
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        // Keep the physics near spawn and high enough for both hulls to free-fall throughout the test.
        BlockPos leftStart = new BlockPos(36, 200, 4);
        BlockPos rightStart = leftStart.east(3);
        level.setChunkForced(2, 0, true);
        var left = assembleUnpoweredDock(level, leftStart, Direction.EAST);
        var right = assembleUnpoweredDock(level, rightStart, Direction.WEST);
        var leftPos = left.getPlot().getCenterBlock();
        var rightPos = right.getPlot().getCenterBlock();

        var yaw = new Quaterniond().rotationY(Math.PI / 4);
        var separation = yaw.transform(new Vector3d(3, 0, 0));
        var leftCenter = worldCenter(left, leftPos);
        var rightCenter = worldCenter(right, rightPos);
        pipeline.teleport(left, yaw.transform(new Vector3d(left.logicalPose().position()).sub(leftCenter))
                .add(leftCenter), yaw);
        pipeline.teleport(right, yaw.transform(new Vector3d(right.logicalPose().position()).sub(rightCenter))
                .add(leftCenter).add(separation), yaw);

        var leftPositive = new BlockWireEndpoint(leftPos, 0);
        var leftReturn = new BlockWireEndpoint(leftPos, 1);
        var rightPositive = new BlockWireEndpoint(rightPos, 0);
        var rightReturn = new BlockWireEndpoint(rightPos, 1);
        test.runAtTickTime(8, () -> {
            test.assertTrue(pipeline.getLinearVelocity(left, new Vector3d()).length() > 2
                            && pipeline.getLinearVelocity(right, new Vector3d()).length() > 2,
                    "Both hulls must already be moving faster than the stationary approach limit");
            level.setBlockAndUpdate(leftPos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(rightPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());

            var circuit = world.prepareForConnection(leftPositive, leftReturn);
            var source = new CurrentSourceWire(leftPositive.getNode(level), leftReturn.getNode(level), 1);
            source.setCurrent(12);
            circuit.addWire(source);
            var load = world.makeSimpleWire(rightPositive, rightReturn, 20);
            test.runAfterDelay(45, () -> {
                test.assertTrue(connected(level, leftPos) && connected(level, rightPos),
                        "The moving sublevels did not dock");
                test.assertTrue(deployed(level, leftPos) && deployed(level, rightPos),
                        "Both sublevel arms must extend before the connection closes");
                var leftContact = left.logicalPose().transformPosition(leftPos.getCenter().relative(Direction.EAST, 0.84375));
                var rightContact = right.logicalPose().transformPosition(rightPos.getCenter().relative(Direction.WEST, 0.84375));
                test.assertTrue(leftContact.distanceTo(rightContact) < 0.02, "The two extended heads did not meet");
                test.assertTrue(Math.abs(load.potentialDifference()) > 10, "Power did not cross between the two hulls");
                test.assertTrue(world.globalGraph.getWires(leftPositive.getNode(level), rightPositive.getNode(level)).size() == 1
                                && world.globalGraph.getWires(leftReturn.getNode(level), rightReturn.getNode(level)).size() == 1,
                        "Expected one contact per pole between distinct sublevels");

                double dockedY = leftContact.y;
                test.runAfterDelay(8, () -> {
                    test.assertTrue(worldCenter(left, leftPos).y < dockedY - 1
                                    && connected(level, leftPos) && connected(level, rightPos),
                            "The joined hulls stopped moving or lost the connection while moving");
                    level.setBlockAndUpdate(leftPos.west(), Blocks.STONE.defaultBlockState());
                    test.assertTrue(!connected(level, leftPos) && !connected(level, rightPos)
                                    && !deployed(level, leftPos) && !deployed(level, rightPos),
                            "Cutting redstone did not disconnect and retract both sublevel docks");
                    test.assertTrue(world.globalGraph.getWires(leftPositive.getNode(level), rightPositive.getNode(level)).isEmpty()
                                    && world.globalGraph.getWires(leftReturn.getNode(level), rightReturn.getNode(level)).isEmpty(),
                            "An electrical contact survived undocking the two sublevels");
                    double releasedDistance = worldCenter(left, leftPos).distance(worldCenter(right, rightPos));
                    pipeline.addLinearAndAngularVelocity(right, new Vector3d(separation).normalize(4), new Vector3d());
                    test.runAfterDelay(5, () -> {
                        test.assertTrue(Math.abs(load.potentialDifference()) < 0.01, "The separated load stayed powered");
                        test.assertTrue(worldCenter(left, leftPos).distance(worldCenter(right, rightPos)) > releasedDistance + 0.4,
                                "The hulls could not move apart after undocking");
                        source.remove();
                        load.remove();
                        level.setChunkForced(2, 0, false);
                        test.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void carrierReleasesDocksIndependently(GameTestHelper test) {
        var level = test.getLevel();
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        BlockPos carrierStart = new BlockPos(68, 200, 2);
        BlockPos planeStart = carrierStart.east(3);
        BlockPos podStart = carrierStart.south(10).below(3);
        level.setChunkForced(4, 0, true);

        var carrier = assembleHull(level, carrierStart, Direction.EAST, 10, 0, 3, 6);
        var plane = assembleHull(level, planeStart, Direction.WEST, 7, 0, 3, 7);
        var pod = assembleUnpoweredDock(level, podStart, Direction.UP);
        var carrierPos = carrier.getPlot().getCenterBlock();
        var planePos = plane.getPlot().getCenterBlock();
        var podPos = pod.getPlot().getCenterBlock();
        var carrierDrop = carrierPos.south(10);
        var carrierDropPower = carrierDrop.above();
        level.setBlockAndUpdate(carrierDrop, dockState(Direction.DOWN));
        level.setBlockAndUpdate(carrierDropPower, Blocks.STONE.defaultBlockState());

        // The underside and upward dock need opposite roll for their keyed contacts to mate.
        var podYaw = new Quaterniond().rotationY(Math.PI);
        var podCenter = worldCenter(pod, podPos);
        pipeline.teleport(pod, podYaw.transform(new Vector3d(pod.logicalPose().position()).sub(podCenter))
                .add(podCenter), podYaw);

        test.runAtTickTime(8, () -> {
            setArmed(level, carrierPos, Direction.EAST, true);
            setArmed(level, planePos, Direction.WEST, true);
        });
        test.runAtTickTime(15, () -> {
            var carrierSecond = carrierPos.south(3);
            var planeSecond = planePos.south(3);
            setArmed(level, carrierSecond, Direction.EAST, true);
            setArmed(level, planeSecond, Direction.WEST, true);
            setArmed(level, carrierPos.south(6), Direction.EAST, true);
            setArmed(level, planePos.south(7), Direction.WEST, true);
            setArmed(level, carrierDrop, Direction.DOWN, true);
            setArmed(level, podPos, Direction.UP, true);
            var planeLoad = feedLoad(level, carrierSecond, planeSecond);
            var podLoad = feedLoad(level, carrierDrop, podPos);

            test.runAfterDelay(53, () -> {
                test.assertTrue(connected(level, carrierPos) && connected(level, planePos)
                                && connected(level, carrierSecond) && connected(level, planeSecond),
                        "Both aligned port pairs must connect to the same plane");
                test.assertTrue(!deployed(level, carrierPos.south(6)) && !deployed(level, planePos.south(7)),
                        "A port pair with mismatched spacing deployed");
                test.assertTrue(connected(level, carrierDrop) && connected(level, podPos),
                        "The underside dock could not hold a separate supply pod");
                test.assertTrue(Math.abs(planeLoad.potentialDifference()) > 10 && Math.abs(podLoad.potentialDifference()) > 10,
                        "Both craft must receive power simultaneously");

                setArmed(level, carrierPos, Direction.EAST, false);
                test.assertTrue(!connected(level, carrierPos) && !connected(level, planePos)
                                && connected(level, carrierSecond) && connected(level, carrierDrop),
                        "Opening the first port pair disturbed a remaining connection");
                var pullAway = worldCenter(plane, planeSecond).sub(worldCenter(carrier, carrierSecond)).normalize(4);
                pipeline.addLinearAndAngularVelocity(plane, pullAway, new Vector3d());
                double podDistance = worldCenter(carrier, carrierDrop).distance(worldCenter(pod, podPos));
                setArmed(level, carrierDrop, Direction.DOWN, false);
                pipeline.addLinearAndAngularVelocity(pod, new Vector3d(0, -5, 0), new Vector3d());

                test.runAfterDelay(8, () -> {
                    test.assertTrue(!connected(level, carrierDrop) && !connected(level, podPos)
                                    && Math.abs(podLoad.potentialDifference()) < 0.01,
                            "The supply pod still has an electrical connection after release");
                    test.assertTrue(worldCenter(carrier, carrierDrop).distance(worldCenter(pod, podPos)) > podDistance + 0.5,
                            "The supply pod could not fall away after releasing its dock");
                    test.assertTrue(connected(level, carrierSecond) && connected(level, planeSecond)
                                    && Math.abs(planeLoad.potentialDifference()) > 10,
                            "The plane did not stay docked and powered after the supply pod dropped");
                    double planeDistance = worldCenter(carrier, carrierSecond).distance(worldCenter(plane, planeSecond));
                    test.assertTrue(Math.abs(planeDistance - 1.6875) < 0.08, "The remaining port did not hold the plane");
                    setArmed(level, planeSecond, Direction.WEST, false);
                    test.assertTrue(!connected(level, carrierSecond) && !connected(level, planeSecond),
                            "The plane's last pair did not disconnect from the carrier");
                    pipeline.addLinearAndAngularVelocity(plane, pullAway, new Vector3d());
                    test.runAfterDelay(6, () -> {
                        test.assertTrue(worldCenter(carrier, carrierSecond).distance(worldCenter(plane, planeSecond))
                                        > planeDistance + 0.4,
                                "The plane could not move away after its last port opened");
                        level.setChunkForced(4, 0, false);
                        test.succeed();
                    });
                });
            });
        });
    }

    private static ServerSubLevel assembleHull(ServerLevel level, BlockPos start, Direction face, int length,
                                               int... dockOffsets) {
        var blocks = new ArrayList<BlockPos>();
        var spine = start.relative(face.getOpposite());
        for (int z = 0; z <= length; z++) {
            var pos = spine.south(z);
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            blocks.add(pos);
        }
        for (int offset : dockOffsets) {
            var pos = start.south(offset);
            level.setBlockAndUpdate(pos, dockState(face));
            blocks.add(pos);
        }
        return SubLevelAssemblyHelper.assembleBlocks(level, start, blocks, new BoundingBox3i(spine, start.south(length)));
    }

    private static BlockState dockState(Direction facing) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("powerdock", "power_dock"))
                .defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    private static void setArmed(ServerLevel level, BlockPos dock, Direction facing, boolean armed) {
        level.setBlockAndUpdate(dock.relative(facing.getOpposite()),
                (armed ? Blocks.REDSTONE_BLOCK : Blocks.STONE).defaultBlockState());
    }

    private static ElectricWire feedLoad(ServerLevel level, BlockPos supply, BlockPos consumer) {
        var world = GlobalElectricNetworks.getWorldNetworks(level);
        var positive = new BlockWireEndpoint(supply, 0);
        var negative = new BlockWireEndpoint(supply, 1);
        var circuit = world.prepareForConnection(positive, negative);
        var source = new CurrentSourceWire(positive.getNode(level), negative.getNode(level), 1);
        source.setCurrent(12);
        circuit.addWire(source);
        return world.makeSimpleWire(new BlockWireEndpoint(consumer, 0), new BlockWireEndpoint(consumer, 1), 20);
    }

    private static ServerSubLevel assembleUnpoweredDock(ServerLevel level, BlockPos pos, Direction facing) {
        var backing = pos.relative(facing.getOpposite());
        level.setBlockAndUpdate(pos, dockState(facing));
        level.setBlockAndUpdate(backing, Blocks.STONE.defaultBlockState());
        return SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos, backing), new BoundingBox3i(pos, backing));
    }

    private static Vector3d worldCenter(ServerSubLevel body, BlockPos pos) {
        return body.logicalPose().transformPosition(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
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
