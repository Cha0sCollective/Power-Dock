package org.cha0scollective.powerdock.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.List;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class PinDockGameTest {
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void routesAllPinsAndRejectsPowerDock(GameTestHelper test) {
        var level = test.getLevel();
        var world = GlobalElectricNetworks.getWorldNetworks(level);
        var pinDockId = ResourceLocation.parse("powerdock:pin_dock");
        test.assertTrue(level.getRecipeManager().byKey(pinDockId).isPresent(), "The Pin Dock recipe did not load");
        var pinDock = BuiltInRegistries.BLOCK.get(pinDockId).defaultBlockState();
        var powerDock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:power_dock")).defaultBlockState();
        var terminals = (IElectric) pinDock.getBlock();
        test.assertTrue(terminals.terminalCount() == 9, "Pin Dock needs eight signal pins and a common");
        for (Direction facing : Direction.values()) {
            var facingState = pinDock.setValue(BlockStateProperties.FACING, facing);
            for (int index = 0; index <= 8; index++) {
                var terminal = terminals.terminal(facingState, index);
                var label = index == 0 ? IDecoratedTerminal.COMMON : Component.translatable("terminal.powerdock.pin", index);
                test.assertTrue(terminal instanceof IDecoratedTerminal decorated && decorated.getName().equals(label)
                                && terminals.terminalIndexAt(facingState, terminal.getOrigin()) == index,
                        "Wrong wire label or hit box on terminal " + index + " facing " + facing);
            }
        }

        level.setChunkForced(6, 0, true);
        var station = new BlockPos(100, -55, 3);
        var launch = station.east(3);
        for (var pos : BlockPos.betweenClosed(station.west().below().north(), launch.east(2).below().south())) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(station, powerDock.setValue(BlockStateProperties.FACING, Direction.EAST));
        level.setBlockAndUpdate(station.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(launch, pinDock.setValue(BlockStateProperties.FACING, Direction.WEST));
        level.setBlockAndUpdate(launch.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var craft = SubLevelAssemblyHelper.assembleBlocks(level, launch, List.of(launch, launch.east()),
                new BoundingBox3i(launch, launch.east()));
        var moving = craft.getPlot().getCenterBlock();
        var localCommon = new BlockWireEndpoint(station, 0);
        var remoteCommon = new BlockWireEndpoint(moving, 0);
        var loads = new ElectricWire[8];

        test.runAtTickTime(20, () -> {
            test.assertTrue(level.getBlockState(station).getAnalogOutputSignal(level, station) == 0
                            && level.getBlockState(moving).getAnalogOutputSignal(level, moving) == 0,
                    "Mismatched ports must stay disconnected");
            test.assertTrue(Math.abs(craft.logicalPose().transformPosition(moving.getCenter()).x - launch.getCenter().x) < 0.15,
                    "A Power Dock pulled in a mismatched Pin Dock");
            level.setBlockAndUpdate(station, pinDock.setValue(BlockStateProperties.FACING, Direction.EAST));
            SubLevelContainer.getContainer(level).physicsSystem().getPipeline().resetVelocity(craft);
            for (int pin = 1; pin <= 8; pin++) {
                var output = new BlockWireEndpoint(station, pin);
                var network = world.prepareForConnection(output, localCommon);
                var source = new CurrentSourceWire(output.getNode(level), localCommon.getNode(level), 1);
                source.setCurrent(4 + pin);
                network.addWire(source);
                loads[pin - 1] = world.makeSimpleWire(new BlockWireEndpoint(moving, pin), remoteCommon, 20);
            }

            test.runAfterDelay(55, () -> {
                test.assertTrue(level.getBlockState(station).getAnalogOutputSignal(level, station) == 15,
                        "Two matching Pin Docks did not close");
                for (int pin = 0; pin <= 8; pin++) {
                    var left = new BlockWireEndpoint(station, pin).getNode(level);
                    for (int peer = 0; peer <= 8; peer++) {
                        var right = new BlockWireEndpoint(moving, peer).getNode(level);
                        test.assertTrue(world.globalGraph.getWires(left, right).size() == (pin == peer ? 1 : 0),
                                "Unexpected wire from terminal " + pin + " to " + peer);
                    }
                    if (pin > 0) {
                        double expected = (4 + pin) * 20 / 21.0;
                        test.assertTrue(Math.abs(Math.abs(loads[pin - 1].potentialDifference()) - expected) < 0.4,
                                "Wrong voltage on signal pin " + pin);
                    }
                }
                level.removeBlock(station.west(), false);
                for (int pin = 0; pin <= 8; pin++) {
                    var left = new BlockWireEndpoint(station, pin).getNode(level);
                    var right = new BlockWireEndpoint(moving, pin).getNode(level);
                    test.assertTrue(world.globalGraph.getWires(left, right).isEmpty(), "Terminal " + pin + " stayed connected");
                }
                level.setChunkForced(6, 0, false);
                test.succeed();
            });
        });
    }
}
