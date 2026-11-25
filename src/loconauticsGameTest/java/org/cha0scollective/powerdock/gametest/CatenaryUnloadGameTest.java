package org.cha0scollective.powerdock.gametest;

import com.lycoris.loconautics.trainphysics.spawn.SableTrainSpawner;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.data.Couple;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.cha0scollective.powerdock.PantographBlockEntity;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.io.IOException;
import java.util.Comparator;

@GameTestHolder("powerdock_loconautics_tests")
@PrefixGameTestTemplate(false)
public final class CatenaryUnloadGameTest {
    private static final int RAIL_Y = 66;
    private static final int TRACK_Z = 424;
    private static final BlockPos FEEDER = new BlockPos(8, RAIL_Y + 7, TRACK_Z);
    private static final BlockPos MIDDLE = FEEDER.east(240);
    private static final BlockPos DOWNSTREAM = MIDDLE.east(60);
    private static final BlockPos END = MIDDLE.east(240);
    private static final int SPANS = 8;
    private static final BlockPos LOCOMOTIVE = END.below(6).west(13);
    private static final BlockPos ROOF = new BlockPos(2, 4, 0);
    private static final Vec3 EAST = new Vec3(1, 0, 0);
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final ChunkPos TRAIN_CHUNK = new ChunkPos(LOCOMOTIVE);
    private static final ChunkPos MIDDLE_CHUNK = new ChunkPos(MIDDLE);
    private static final ChunkPos FEEDER_CHUNK = new ChunkPos(FEEDER);
    private static final TicketType<ChunkPos> TRAIN_VIEW = TicketType.create("powerdock_test_train_view",
            Comparator.comparingLong(ChunkPos::toLong));

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void overheadPowerSurvivesUnloadedHangers(GameTestHelper test) {
        var fixture = new Fixture(test);
        fixture.build();
        test.runAtTickTime(20, fixture::assemble);
        test.runAtTickTime(60, fixture::linkAndPower);
        test.runAtTickTime(90, fixture::releaseHangerChunks);
        test.onEachTick(fixture::check);
    }

    private static final class Fixture {
        private final GameTestHelper test;
        private final ServerLevel level;
        private PantographBlockEntity pickup;
        private CatenaryHangerBlockEntity originalMiddle;
        private ElectricWire load;
        private TrackGraph track;
        private TrackEdge outward, inward;
        private long start;
        private int unloadedTicks;

        Fixture(GameTestHelper test) {
            this.test = test;
            level = test.getLevel();
        }

        void build() {
            // Put the middle hanger outside even the chunk-generation radius of the feeder and train tickets.
            for (int i = 0; i <= SPANS; i++) setHangerChunkForced(i, true);
            level.setChunkForced(TRAIN_CHUNK.x, TRAIN_CHUNK.z, true);
            for (int x = LOCOMOTIVE.getX() - 6; x <= LOCOMOTIVE.getX() + 9; x++) {
                var pos = new BlockPos(x, RAIL_Y, TRACK_Z);
                level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
            }
            for (int x = -1; x <= 6; x++) for (int z = -2; z <= 2; z++) {
                level.setBlockAndUpdate(LOCOMOTIVE.above().offset(x, 0, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            }
            for (int x : new int[]{0, 5}) {
                level.setBlockAndUpdate(LOCOMOTIVE.east(x), AllBlocks.SMALL_BOGEY.getDefaultState()
                        .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
            }
            var roof = LOCOMOTIVE.offset(ROOF);
            level.setBlockAndUpdate(roof.below(2), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(roof.below(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            var pantograph = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:pantograph"));
            var raised = (BooleanProperty) pantograph.getStateDefinition().getProperty("raised");
            level.setBlockAndUpdate(roof, pantograph.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST).setValue(raised, true));
            level.addFreshEntity(new SuperGlueEntity(level, new AABB(Vec3.atLowerCornerOf(LOCOMOTIVE.west().north(3)),
                    Vec3.atLowerCornerOf(LOCOMOTIVE.east(7).above(5).south(4)))));
        }

        void assemble() {
            TrackPropagator.onRailAdded(level, LOCOMOTIVE.below(), level.getBlockState(LOCOMOTIVE.below()));
            var train = SableTrainSpawner.assembleAt(level, LOCOMOTIVE, EAST, null, "Catenary unload train");
            test.assertTrue(train != null && train.car().bogeys().size() == 2, "The test locomotive did not assemble");
            var trainBody = (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(train.car().subLevelId());
            var pos = trainBody.getPlot().getCenterBlock().offset(ROOF);
            pickup = (PantographBlockEntity) level.getBlockEntity(pos);
            test.assertTrue(pickup != null, "The pantograph did not assemble with the locomotive");
        }

        void linkAndPower() {
            // Keep the long graph edge independent of the short section used to assemble the locomotive.
            track = new TrackGraph();
            var firstRail = FEEDER.below(7).west(4);
            var lastRail = END.below(7).east(4);
            var curve = new BezierConnection(Couple.create(firstRail, lastRail),
                    Couple.create(Vec3.atBottomCenterOf(firstRail).add(0.5, 0, 0), Vec3.atBottomCenterOf(lastRail).add(-0.5, 0, 0)),
                    Couple.create(EAST, EAST.reverse()), Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);
            var first = node(curve, true, 10000);
            var end = node(curve, false, 10001);
            outward = new TrackEdge(first, end, curve, TrackMaterial.ANDESITE);
            inward = new TrackEdge(end, first, curve.secondary(), TrackMaterial.ANDESITE);
            restoreTrack();
            Create.RAILWAYS.putGraph(track);

            var hanger = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger"));
            for (int i = 0; i <= SPANS; i++) {
                level.setBlockAndUpdate(hangerPos(i), hanger.defaultBlockState());
            }
            var feeder = (CatenaryHangerBlockEntity) level.getBlockEntity(FEEDER);
            originalMiddle = (CatenaryHangerBlockEntity) level.getBlockEntity(MIDDLE);
            var previous = feeder;
            for (int i = 1; i <= SPANS; i++) {
                var pos = hangerPos(i);
                var next = (CatenaryHangerBlockEntity) level.getBlockEntity(pos);
                test.assertTrue(previous.linkTo(next), "Could not link the hanger at " + pos);
                previous = next;
            }
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            var positive = new BlockWireEndpoint(FEEDER, 0);
            var negative = new BlockWireEndpoint(FEEDER, 1);
            var circuit = grid.prepareForConnection(positive, negative);
            var source = new CurrentSourceWire(positive.getNode(level), negative.getNode(level), 1);
            source.setCurrent(30);
            circuit.addWire(source);
            var pickupPos = pickup.getBlockPos();
            load = grid.makeSimpleWire(new BlockWireEndpoint(pickupPos, 0), new BlockWireEndpoint(pickupPos, 1), 20);
            test.assertTrue(load != null, "Could not attach the Power Grid test load");
        }

        private TrackNode node(BezierConnection curve, boolean first, int id) {
            var start = curve.starts.get(first);
            var location = new TrackNodeLocation(start).in(level);
            location.yOffsetPixels = curve.yOffsetAt(start);
            var node = new TrackNode(location, id, UP);
            track.addNode(node);
            return node;
        }

        private void restoreTrack() {
            track.putConnection(outward.node1, outward.node2, outward);
            track.putConnection(inward.node1, inward.node2, inward);
            track.invalidateBounds();
        }

        private BlockPos hangerPos(int index) {
            return FEEDER.east(index * 60);
        }

        private void setHangerChunkForced(int index, boolean forced) {
            var chunk = new ChunkPos(hangerPos(index));
            level.setChunkForced(chunk.x, chunk.z, forced);
        }

        void releaseHangerChunks() {
            requirePower();
            level.getChunkSource().addRegionTicket(TRAIN_VIEW, TRAIN_CHUNK, 2, TRAIN_CHUNK);
            for (int i = 1; i <= SPANS; i++) setHangerChunkForced(i, false);
            level.setChunkForced(TRAIN_CHUNK.x, TRAIN_CHUNK.z, false);
            start = level.getGameTime();
        }

        void check() {
            if (start == 0) return;
            boolean chunkAbsent = level.getChunkSource().getChunkNow(MIDDLE_CHUNK.x, MIDDLE_CHUNK.z) == null;
            boolean unloaded = chunkAbsent && originalMiddle.isChunkUnloaded();
            if (unloadedTicks == 0 && !unloaded) {
                requirePower();
                test.assertTrue(level.getGameTime() - start < 500, "Middle hanger after releasing tickets: chunk absent="
                        + chunkAbsent + ", block entity unloaded=" + originalMiddle.isChunkUnloaded());
                return;
            }
            test.assertTrue(unloaded, "The middle hanger reloaded during the test");
            test.assertTrue(level.getForcedChunks().contains(FEEDER_CHUNK.toLong())
                    && !level.getForcedChunks().contains(MIDDLE_CHUNK.toLong())
                    && !level.getForcedChunks().contains(TRAIN_CHUNK.toLong()), "The overhead route is force-loaded");
            unloadedTicks++;
            if (unloadedTicks <= 40) requirePower();
            switch (unloadedTicks) {
                case 40 -> {
                    requireSavedLink();
                    System.out.printf(java.util.Locale.ROOT, "CATENARY_UNLOADED held %d ticks at %.5f V%n",
                            unloadedTicks, Math.abs(load.potentialDifference()));
                    track.disconnectNodes(outward.node1, outward.node2);
                }
                case 55 -> {
                    requireOpen("a severed track segment");
                    restoreTrack();
                }
                case 75 -> {
                    requirePower();
                    level.removeBlock(FEEDER, false);
                }
                case 95 -> {
                    requireOpen("the removed feeder");
                    Create.RAILWAYS.removeGraph(track);
                    level.setChunkForced(FEEDER_CHUNK.x, FEEDER_CHUNK.z, false);
                    level.getChunkSource().removeRegionTicket(TRAIN_VIEW, TRAIN_CHUNK, 2, TRAIN_CHUNK);
                    test.succeed();
                }
                default -> {}
            }
        }

        private void requireSavedLink() {
            try {
                var storage = level.getDataStorage();
                storage.save();
                IOUtilities.waitUntilIOWorkerComplete();
                var saved = storage.readTagFromDisk("powerdock_catenary", null, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
                var links = saved.getCompound("data").getList("Links", Tag.TAG_COMPOUND);
                test.assertTrue(links.stream().anyMatch(tag -> tag instanceof net.minecraft.nbt.CompoundTag link
                                && link.getLong("From") == MIDDLE.asLong() && link.getLong("To") == DOWNSTREAM.asLong()
                                && !link.getCompound("Route").isEmpty() && link.getList("Span", Tag.TAG_COMPOUND).size() > 1),
                        "World data did not save the span from the unloaded hanger");
            } catch (IOException error) {
                test.fail(error.toString());
            }
        }

        private void requirePower() {
            test.assertTrue(pickup.connected() && Math.abs(load.potentialDifference()) > 25,
                    "The pantograph lost power while the saved route was intact");
        }

        private void requireOpen(String reason) {
            test.assertTrue(!pickup.connected() && Math.abs(load.potentialDifference()) < 0.01,
                    "The pantograph stayed powered after " + reason);
        }
    }
}
