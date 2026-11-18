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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.PowerDockBlockEntity;
import org.joml.Vector3d;

import java.util.ArrayList;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class DockingInterruptionGameTest {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cuttingRedstoneCancelsApproach(GameTestHelper test) {
        var rig = fixture(test.getLevel(), 10, 0);
        var level = rig.level();
        var local = rig.station(0);
        var remote = rig.remote(0);
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        var releasedX = new double[1];
        rig.arm(0);

        test.startSequence().thenWaitUntil(() -> test.assertTrue(approaching(local),
                "Waiting for the arms to extend before making contact"))
                .thenExecute(() -> {
                    level.removeBlock(local.getBlockPos().west(), false);
                    test.assertTrue(retracting(local) && retracting(remote),
                            "Loss of redstone did not start retracting both arms");
                    releasedX[0] = rig.shipX();
                    pipeline.addLinearAndAngularVelocity(rig.ship(), new Vector3d(6, 0, 0), new Vector3d());
                }).thenIdle(9).thenExecute(() -> {
                    test.assertTrue(rig.shipX() > releasedX[0] + 0.25, "The ship was still held after aborting alignment");
                    test.assertTrue(!retracting(local) && !retracting(remote), "The arms did not finish retracting");
                    test.assertTrue(!flag(local, "connected") && !flag(remote, "connected"),
                            "A canceled alignment made electrical contact");
                    rig.unforce();
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void secondPairFinishesAfterBreakingFirst(GameTestHelper test) {
        var rig = fixture(test.getLevel(), 12, 3);
        var first = rig.station(0);
        var second = rig.station(3);
        var other = rig.remote(3);
        rig.arm(0);

        test.startSequence().thenWaitUntil(() -> test.assertTrue(approaching(first), "Waiting for the first pair to capture"))
                .thenExecute(() -> rig.arm(3))
                .thenWaitUntil(() -> test.assertTrue(approaching(second), "Waiting for the second pair to extend"))
                .thenExecute(() -> {
                    test.assertTrue(approaching(first), "The leading pair must be removed while both pairs are aligning");
                    rig.level().removeBlock(first.getBlockPos(), false);
                    test.assertTrue(retracting(rig.remote(0)) && approaching(second) && approaching(other),
                            "Breaking the leading port interrupted the remaining pair");
                }).thenWaitUntil(() -> test.assertTrue(flag(second, "connected") && flag(other, "connected"),
                        "The remaining pair did not finish aligning and close"))
                .thenExecute(rig::unforce).thenSucceed();
    }

    private static boolean flag(PowerDockBlockEntity dock, String name) {
        var state = dock.getBlockState();
        return Boolean.TRUE.equals(state.getValue(state.getBlock().getStateDefinition().getProperty(name)));
    }

    private static boolean approaching(PowerDockBlockEntity dock) {
        return flag(dock, "deployed") && !flag(dock, "connected");
    }

    private static boolean retracting(PowerDockBlockEntity dock) {
        return flag(dock, "retracting") && !flag(dock, "deployed");
    }

    private static Rig fixture(ServerLevel level, int chunkX, int span) {
        var station = new BlockPos(chunkX * 16 + 3, -55, 4);
        var launch = station.east(3);
        level.setChunkForced(chunkX, 0, true);
        for (var pos : BlockPos.betweenClosed(station.west().below().north(), launch.east(2).below().south(span + 1))) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("powerdock", "power_dock"))
                .defaultBlockState();
        var blocks = new ArrayList<BlockPos>();
        for (int offset = 0; offset <= span; offset++) {
            var shipPort = launch.south(offset);
            var back = shipPort.east();
            if (offset == 0 || offset == span) {
                level.setBlockAndUpdate(station.south(offset), state.setValue(BlockStateProperties.FACING, Direction.EAST));
                level.setBlockAndUpdate(shipPort, state.setValue(BlockStateProperties.FACING, Direction.WEST));
                level.setBlockAndUpdate(back, Blocks.REDSTONE_BLOCK.defaultBlockState());
                blocks.add(shipPort);
            } else {
                level.setBlockAndUpdate(back, Blocks.STONE.defaultBlockState());
            }
            blocks.add(back);
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, launch, blocks, new BoundingBox3i(launch, launch.east().south(span)));
        return new Rig(level, station, ship);
    }

    private record Rig(ServerLevel level, BlockPos fixed, ServerSubLevel ship) {
        PowerDockBlockEntity station(int offset) {
            return (PowerDockBlockEntity) level.getBlockEntity(fixed.south(offset));
        }

        PowerDockBlockEntity remote(int offset) {
            return (PowerDockBlockEntity) level.getBlockEntity(ship.getPlot().getCenterBlock().south(offset));
        }

        void arm(int offset) {
            level.setBlockAndUpdate(fixed.south(offset).west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        }

        double shipX() {
            return ship.logicalPose().transformPosition(ship.getPlot().getCenterBlock().getCenter()).x;
        }

        void unforce() {
            level.setChunkForced(fixed.getX() >> 4, fixed.getZ() >> 4, false);
        }
    }
}
