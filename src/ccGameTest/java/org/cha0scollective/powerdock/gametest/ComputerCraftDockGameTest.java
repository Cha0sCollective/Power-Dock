package org.cha0scollective.powerdock.gametest;

import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.peripheral.modem.wired.CableBlock;
import dan200.computercraft.shared.peripheral.modem.wired.WiredModemFullBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GameTestHolder("powerdock_cc_tests")
@PrefixGameTestTemplate(false)
public final class ComputerCraftDockGameTest {
    @GameTest(template = "empty", timeoutTicks = 450)
    public static void carrierComputerControlsPlaneConnection(GameTestHelper test) {
        var level = test.getLevel();
        level.setChunkForced(8, 0, true);
        var carrierStart = new BlockPos(132, 120, 4);
        var planeStart = carrierStart.east(3);
        var modem = block("computercraft:wired_modem_full");
        var computer = block("computercraft:computer_normal");
        var carrierBlocks = hull(carrierStart, Direction.EAST);
        carrierBlocks.put(carrierStart.west(), block("computercraft:cable").setValue(CableBlock.CABLE, true));
        carrierBlocks.put(carrierStart.south().west(), modem);
        carrierBlocks.put(carrierStart.south(), computer);
        var planeBlocks = hull(planeStart, Direction.WEST);
        planeBlocks.put(planeStart.east(), modem);
        planeBlocks.put(planeStart.east().south(), Blocks.CHEST.defaultBlockState());
        planeBlocks.put(planeStart.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());

        for (var entry : carrierBlocks.entrySet()) level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        var carrier = SubLevelAssemblyHelper.assembleBlocks(level, carrierStart, List.copyOf(carrierBlocks.keySet()),
                new BoundingBox3i(carrierStart.west(), carrierStart.south(4)));
        var carrierPort = carrier.getPlot().getCenterBlock();
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        var center = new Vector3d(carrierPort.getX() + 0.5, carrierPort.getY() + 0.5, carrierPort.getZ() + 0.5);
        var hover = pipeline.addConstraint(carrier, null, new GenericConstraintConfiguration(center,
                carrier.logicalPose().transformPosition(center, new Vector3d()), new Quaterniond(), new Quaterniond(),
                EnumSet.allOf(ConstraintJointAxis.class)));
        test.assertTrue(hover != null, "Could not stage the carrier in flight");

        for (var entry : planeBlocks.entrySet()) level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        var plane = SubLevelAssemblyHelper.assembleBlocks(level, planeStart, List.copyOf(planeBlocks.keySet()),
                new BoundingBox3i(planeStart.below(), planeStart.east().south(4)));
        var planePort = plane.getPlot().getCenterBlock();
        WritableMount[] files = new WritableMount[1];
        SubLevelData[] unloadedPlane = new SubLevelData[1];
        test.runAtTickTime(8, () -> {
            test.assertTrue(level.getBlockEntity(carrierPort.south()) instanceof AbstractComputerBlockEntity,
                    "The carrier did not assemble the computer block entity");
            var carrierComputer = ((AbstractComputerBlockEntity) level.getBlockEntity(carrierPort.south())).createServerComputer();
            files[0] = carrierComputer.createRootMount();
            var inventory = (ChestBlockEntity) level.getBlockEntity(planePort.east().south());
            inventory.setItem(0, new ItemStack(Items.COPPER_INGOT, 3));
            var planeModem = (WiredModemFullBlockEntity) level.getBlockEntity(planePort.east());
            planeModem.use(FakePlayerFactory.getMinecraft(level));
            feedSignal(level, carrierPort, planePort);
            try (var source = ComputerCraftDockGameTest.class.getResourceAsStream("/data/powerdock_cc_tests/programs/carrier.lua")) {
                if (source == null) throw new IOException("The carrier Lua test is missing");
                if (files[0].exists("result.txt")) files[0].delete("result.txt");
                try (var target = files[0].openFile("startup.lua", Set.of(StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    var data = ByteBuffer.wrap(source.readAllBytes());
                    while (data.hasRemaining()) target.write(data);
                }
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            carrierComputer.turnOn();
        });
        test.startSequence().thenWaitUntil(() -> waitForMarker(test, files[0], "ready-unload"))
                .thenExecute(() -> {
                    // Exercise real Sable plot serialization and unload while the carrier and its Lua computer stay loaded.
                    unloadedPlane[0] = SubLevelSerializer.toData(plane, List.of());
                    SubLevelContainer.getContainer(level).removeSubLevel(plane, SubLevelRemovalReason.UNLOADED);
                    test.assertTrue(plane.isRemoved() && !carrier.isRemoved(), "The plane did not unload independently");
                })
                .thenWaitUntil(() -> waitForMarker(test, files[0], "observed-unload"))
                .thenExecute(() -> {
                    test.assertTrue(!connected(level, carrierPort) && !connected(level, carrierPort.south(4)),
                            "A port still reported a connection with the plane absent");
                    var restored = SubLevelSerializer.fullyLoad(level, unloadedPlane[0]);
                    test.assertTrue(restored != null && restored.getUniqueId().equals(plane.getUniqueId())
                                    && restored.getPlot().getCenterBlock().equals(planePort),
                            "The unloaded plane could not be restored to its original plot");
                })
                .thenWaitUntil(() -> waitForMarker(test, files[0], "result.txt")).thenExecute(() -> {
            try (var file = files[0].openForRead("result.txt")) {
                var data = ByteBuffer.allocate((int) file.size());
                while (data.hasRemaining() && file.read(data) >= 0) { }
                var result = new String(data.array(), StandardCharsets.UTF_8).strip();
                test.assertTrue(result.equals("ok"), "Carrier Lua test failed: " + result);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            test.assertTrue(connected(level, carrierPort.south(4)) && connected(level, planePort.south(4)),
                    "The standard Power Dock pair must still hold the plane after the Pin Docks open");
            test.assertTrue(!connected(level, carrierPort) && !connected(level, planePort),
                    "The Lua program did not leave the Pin Docks open");
            level.setChunkForced(8, 0, false);
        }).thenSucceed();
    }

    private static void waitForMarker(GameTestHelper test, WritableMount files, String marker) {
        try {
            // An early Lua assertion should surface in the result check instead of waiting for a checkpoint forever.
            test.assertTrue(files != null && (files.exists(marker) || files.exists("result.txt")),
                    "Waiting for carrier Lua checkpoint " + marker);
            if (!marker.equals("result.txt") && !files.exists(marker)) {
                try (var file = files.openForRead("result.txt")) {
                    var data = ByteBuffer.allocate((int) file.size());
                    while (data.hasRemaining() && file.read(data) >= 0) { }
                    throw new IllegalStateException("Carrier Lua ended before " + marker + ": "
                            + new String(data.array(), StandardCharsets.UTF_8).strip());
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Map<BlockPos, BlockState> hull(BlockPos pivot, Direction facing) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (int offset = 0; offset <= 4; offset++) {
            blocks.put(pivot.south(offset).relative(facing.getOpposite()), Blocks.STONE.defaultBlockState());
        }
        blocks.put(pivot, block("powerdock:pin_dock").setValue(BlockStateProperties.FACING, facing));
        var regularDock = pivot.south(4);
        blocks.put(regularDock, block("powerdock:power_dock").setValue(BlockStateProperties.FACING, facing));
        blocks.put(regularDock.relative(facing.getOpposite()), Blocks.REDSTONE_BLOCK.defaultBlockState());
        return blocks;
    }

    private static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private static boolean connected(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getAnalogOutputSignal(level, pos) == 15;
    }

    private static void feedSignal(ServerLevel level, BlockPos carrier, BlockPos plane) {
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var signal = new BlockWireEndpoint(carrier, 3);
        var common = new BlockWireEndpoint(carrier, 0);
        var circuit = grid.prepareForConnection(signal, common);
        var supply = new CurrentSourceWire(signal.getNode(level), common.getNode(level), 1);
        supply.setCurrent(12);
        circuit.addWire(supply);
        grid.makeSimpleWire(new BlockWireEndpoint(plane, 3), new BlockWireEndpoint(plane, 0), 20);
    }
}
