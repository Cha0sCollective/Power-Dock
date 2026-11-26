package org.cha0scollective.powerdock.gametest;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryContacts.Terminals;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

@GameTestHolder("powerdock_catenary_recovery")
@PrefixGameTestTemplate(false)
public final class CatenaryRecoveryGameTest {
    private static final String DATA = "powerdock_catenary";

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void restoresCatenaryInFreshProcess(GameTestHelper test) {
        String phase = System.getProperty("powerdock.test.catenaryRecovery");
        boolean legacy = phase.endsWith("-beta3");
        boolean restore = phase.startsWith("restore");
        var level = test.getLevel();
        var fixture = new CatenaryPassingLoopGameTest.Fixture(test, new BlockPos(0, 66, 1300));
        var load = new ElectricWire[1];
        var west = fixture.west.south(3);
        var east = fixture.east.south(3);
        if (!restore) {
            fixture.place();
            test.runAtTickTime(25, () -> {
                if (legacy) CatenaryWireChecks.linkVia(test, fixture.west, fixture.main, fixture.east);
                else fixture.linkRoutes();
                placeSupply(level, west, 24);
                placeSupply(level, east, 24);
            });
            test.runAtTickTime(45, () -> {
                wireSupply(test, west, fixture.west);
                wireSupply(test, east, fixture.east);
            });
        } else {
            // GameTestServer starts around a new test location. Reapply the feeder tickets in the test world.
            for (var pos : new BlockPos[]{fixture.west, fixture.east}) {
                var chunk = new ChunkPos(pos);
                level.setChunkForced(chunk.x, chunk.z, false);
                level.setChunkForced(chunk.x, chunk.z, true);
            }
        }
        test.runAtTickTime(65, () -> {
            var terminals = Terminals.block(fixture.east);
            load[0] = GlobalElectricNetworks.getWorldNetworks(level).makeSimpleWire(terminals.supply(), terminals.returnWire(), 10);
            test.assertTrue(load[0] != null, "Could not connect the test load to a saved hanger");
        });
        test.runAtTickTime(100, () -> {
            var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(fixture.west);
            test.assertTrue(hanger.linkedTo(fixture.east) && !hanger.overheadWireEnds().isEmpty(),
                    "The selected route did not restore");
            checkVoltage(test, load[0]);
            if (restore) level.removeBlock(east, false);
        });
        test.runAtTickTime(135, () -> {
            checkVoltage(test, load[0]);
            var data = saveAndRead(level);
            var links = data.getList("Links", Tag.TAG_COMPOUND);
            test.assertTrue(links.size() == (legacy ? 1 : 2) && data.getInt("GraphVersion") == 1,
                    "The saved graph did not contain the expected routes");
            var ids = links.stream().map(entry -> ((CompoundTag) entry).getUUID("Id")).map(UUID::toString).sorted().toList();
            if (legacy && !restore) saveLegacyFormat(level, links);
            System.out.printf(java.util.Locale.ROOT, "CATENARY_RECOVERY phase=%s volts=%.3f spans=%s%n",
                    phase, load[0].potentialDifference(), ids);
            test.succeed();
        });
    }

    static void placeSupply(ServerLevel level, BlockPos pos, float voltage) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powergrid:creative_voltage_source"));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var source = (CreativeSourceBlockEntity) level.getBlockEntity(pos);
        source.getBehaviour(ScrollValueBehaviour.TYPE).setValue(Math.round(voltage));
        source.setChanged();
    }

    static void wireSupply(GameTestHelper test, BlockPos source, BlockPos hanger) {
        var player = test.makeMockPlayer(GameType.CREATIVE);
        var wire = BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:insulated_copper_wire"));
        for (int pin = 0; pin < 2; pin++) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wire, 64));
            var context = new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hanger.getCenter(), Direction.UP, hanger, false));
            var result = IElectric.makeHangingWireConnection(test.getLevel(),
                    new BlockWireEndpoint(source, pin), new BlockWireEndpoint(hanger, pin), context);
            test.assertTrue(result.consumesAction(), "Power Grid failed to connect a feeder wire at " + hanger + ", pin " + pin);
        }
    }

    private static void checkVoltage(GameTestHelper test, ElectricWire load) {
        test.assertTrue(load.potentialDifference() > 22, "The line delivered only " + load.potentialDifference() + " V");
    }

    private static CompoundTag saveAndRead(ServerLevel level) {
        try {
            var storage = level.getDataStorage();
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            return storage.readTagFromDisk(DATA, null, SharedConstants.getCurrentVersion().getDataVersion().getVersion())
                    .getCompound("data");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void saveLegacyFormat(ServerLevel level, ListTag links) {
        var oldLinks = links.copy();
        for (var item : oldLinks) {
            var entry = (CompoundTag) item;
            entry.remove("Id");
            entry.remove("Legacy");
        }
        var legacy = new SavedData() {
            @Override
            public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                tag.put("Links", oldLinks);
                return tag;
            }
        };
        level.getDataStorage().set(DATA, legacy);
        legacy.setDirty();
        level.getServer().saveEverything(true, true, true);
        IOUtilities.waitUntilIOWorkerComplete();
    }
}
