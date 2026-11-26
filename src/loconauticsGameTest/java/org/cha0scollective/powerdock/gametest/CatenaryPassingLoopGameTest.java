package org.cha0scollective.powerdock.gametest;

import com.lycoris.loconautics.content.bearingaxle.BearingAxleBlockEntity;
import com.lycoris.loconautics.content.transmission.TransmissionBlockEntity;
import com.lycoris.loconautics.trainphysics.SableTrain;
import com.lycoris.loconautics.trainphysics.spawn.SableTrainSpawner;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryContacts.Terminals;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.cha0scollective.powerdock.PantographBlockEntity;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder("powerdock_loconautics_tests")
@PrefixGameTestTemplate(false)
public final class CatenaryPassingLoopGameTest {
    private static final BlockPos RAIL = new BlockPos(0, 66, 788);
    private static final Vec3 FORWARD = new Vec3(1, 0, 0);
    private static final Vec3 UP = new Vec3(0, 1, 0);

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void buildAndCutPassingLoop(GameTestHelper test) {
        var layout = new Fixture(test, RAIL);
        layout.place();
        test.runAtTickTime(40, layout::linkRoutes);
        test.runAtTickTime(55, layout::wire);
        test.runAtTickTime(75, layout::severTrack);
        test.runAtTickTime(95, layout::restoreTrack);
        test.runAtTickTime(115, layout::cutMain);
        test.runAtTickTime(145, layout::cutSiding);
        test.runAtTickTime(175, layout::checkEast);
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void pantographDrivesThroughBothTurnoutsInBothDirections(GameTestHelper test) {
        var layout = new Fixture(test, RAIL.south(256), 62);
        layout.place();
        var drive = new Drive(layout);
        drive.build();
        test.runAtTickTime(30, drive::assemble);
        test.runAtTickTime(50, drive::energize);
        test.runAtTickTime(75, drive::start);
        test.onEachTick(drive::tick);
    }

    @GameTest(template = "empty", timeoutTicks = 2100)
    public static void reversingLoopKeepsOnboardPolarity(GameTestHelper test) {
        var layout = new Fixture(test, RAIL.south(800));
        var loop = new ReversingLoop(layout);
        loop.build();
        test.runAtTickTime(30, loop.drive::assemble);
        test.runAtTickTime(50, loop::energize);
        test.runAtTickTime(75, loop::depart);
        test.onEachTick(loop::tick);
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void diamondCrossingKeepsTheLinesIsolated(GameTestHelper test) {
        var center = new BlockPos(8, 66, 1848);
        var layout = new Fixture(test, center);
        var level = test.getLevel();
        var chunk = new ChunkPos(center);
        level.setChunkForced(chunk.x, chunk.z, true);
        for (int distance = -6; distance <= 6; distance++) {
            layout.track(center.east(distance), TrackShape.XO);
            layout.track(center.south(distance), TrackShape.ZO);
        }
        layout.track(center, TrackShape.CR_O);
        var hanger = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger"));
        var west = center.west(4).above(7);
        var east = center.east(4).above(7);
        var north = center.north(4).above(7);
        var south = center.south(4).above(7);
        for (var endpoint : List.of(west, east, north, south)) {
            var rail = endpoint.below(7);
            TrackPropagator.onRailAdded(level, rail, level.getBlockState(rail));
            level.setBlockAndUpdate(endpoint, hanger.defaultBlockState());
        }
        var loads = new ElectricWire[2];
        test.runAtTickTime(30, () -> {
            CatenaryWireChecks.link(test, west, east, false);
            CatenaryWireChecks.link(test, north, south, false);
            layout.source(west, 24);
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            var x = Terminals.block(east);
            var z = Terminals.block(south);
            loads[0] = grid.makeSimpleWire(x.supply(), x.returnWire(), 10);
            loads[1] = grid.makeSimpleWire(z.supply(), z.returnWire(), 10);
        });
        test.runAtTickTime(60, () -> {
            test.assertTrue(loads[0].potentialDifference() > 21 && Math.abs(loads[1].potentialDifference()) < 0.01,
                    "One line transferred power to the other at the diamond crossing");
            layout.source(north, 12);
        });
        test.runAtTickTime(90, () -> {
            test.assertTrue(loads[0].potentialDifference() > 21 && Math.abs(loads[1].potentialDifference() - 10.9) < 0.1,
                    "The separately fed crossing lines did not retain their own voltages");
            level.setChunkForced(chunk.x, chunk.z, false);
            test.succeed();
        });
    }

    static final class Fixture {
        private final GameTestHelper test;
        private final ServerLevel level;
        private final BlockPos rail;
        final BlockPos westRail, west, east, main, siding;
        private ElectricWire load;
        private double westCurrent;
        private double completeCurrent;
        private TrackGraph editedGraph;
        private TrackEdge removedEdge, removedReverse;
        private static final double SUPPLY = 24;

        Fixture(GameTestHelper test, BlockPos rail) {
            this(test, rail, 59);
        }

        Fixture(GameTestHelper test, BlockPos rail, int eastEnd) {
            this.test = test;
            level = test.getLevel();
            this.rail = rail;
            westRail = rail.east(3);
            west = westRail.above(7);
            east = rail.east(eastEnd).above(7);
            main = rail.east(30);
            siding = main.south(6);
        }

        void place() {
            for (int x = 0; x <= 4; x++) level.setChunkForced(x, rail.getZ() >> 4, true);
            for (int x = 1; x <= 66; x++) track(rail.east(x));
            for (int x = 25; x <= 35; x++) track(rail.east(x).south(6));
            curve(rail.east(10), rail.east(25).south(6));
            curve(rail.east(35).south(6), rail.east(50));
            for (var pos : new BlockPos[]{westRail, main, siding, east.below(7)}) {
                TrackPropagator.onRailAdded(level, pos, level.getBlockState(pos));
            }
            var hanger = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger"));
            level.setBlockAndUpdate(west, hanger.defaultBlockState());
            level.setBlockAndUpdate(east, hanger.defaultBlockState());
        }

        private void track(BlockPos pos) {
            track(pos, TrackShape.XO);
        }

        private void track(BlockPos pos, TrackShape shape) {
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, shape));
        }

        private BezierConnection curve(BlockPos from, BlockPos to) {
            return curve(from, to, FORWARD, FORWARD.reverse());
        }

        private BezierConnection curve(BlockPos from, BlockPos to, Vec3 enter, Vec3 exit) {
            var first = level.getBlockState(from).setValue(TrackBlock.HAS_BE, true);
            var second = level.getBlockState(to).setValue(TrackBlock.HAS_BE, true);
            level.setBlockAndUpdate(from, first);
            level.setBlockAndUpdate(to, second);
            var track = AllBlocks.TRACK.get();
            var connection = new BezierConnection(Couple.create(from, to),
                    Couple.create(track.getCurveStart(level, from, first, enter), track.getCurveStart(level, to, second, exit)),
                    Couple.create(enter, exit), Couple.create(UP, UP), true, false, track.getMaterial());
            ((TrackBlockEntity) level.getBlockEntity(from)).addConnection(connection);
            ((TrackBlockEntity) level.getBlockEntity(to)).addConnection(connection.secondary());
            return connection;
        }

        void wire() {
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            long dormant = virtualNodes();
            test.assertTrue(dormant == 0, "An unpowered passing loop allocated " + dormant + " solver nodes");
            CatenaryWireChecks.watchWireChunk(test, main);
            source(west, SUPPLY);
            source(east, SUPPLY);
            var terminals = Terminals.block(east);
            load = grid.makeSimpleWire(terminals.supply(), terminals.returnWire(), 10);
            test.assertTrue(load != null, "The east turnout's PG load could not be attached");
        }

        private long virtualNodes() {
            return GlobalElectricNetworks.getWorldNetworks(level).globalExternalNodes.keySet().stream()
                    .filter(endpoint -> endpoint.type() == WireEndpointType.IMAGINARY)
                    .map(endpoint -> endpoint.getExactPosition(level))
                    .filter(position -> position.x >= 0 && position.x < 67 && Math.abs(position.z - rail.getZ()) < 10)
                    .count();
        }

        void severTrack() {
            completeCurrent = westFeederCurrent();
            long active = virtualNodes();
            test.assertTrue(completeCurrent > 0.5 && active == 8, "The active line needed eight junction nodes, found " + active);
            var fork = turnout(false);
            editedGraph = fork.graph();
            removedEdge = fork.siding();
            removedReverse = editedGraph.getConnection(Couple.create(removedEdge.node2, removedEdge.node1));
            editedGraph.disconnectNodes(removedEdge.node1, removedEdge.node2);
        }

        private Turnout turnout(boolean westbound) {
            return turnout(rail.east(westbound ? 50 : 10), westbound);
        }

        private Turnout turnout(BlockPos branchPos, boolean westbound) {
            for (var graph : Create.RAILWAYS.trackNetworks.values()) {
                for (var node : graph.getNodes()) {
                    var connections = graph.getConnectionsFrom(graph.locateNode(node));
                    if (connections == null) continue;
                    for (var edge : connections.values()) {
                        if (!edge.isTurn() || !edge.getTurn().bePositions.getFirst().equals(branchPos)) continue;
                        var straight = connections.values().stream().filter(exit -> !exit.isTurn())
                                .filter(exit -> westbound ? exit.getDirection(true).x < 0 : exit.getDirection(true).x > 0)
                                .findFirst().orElseThrow(() -> new IllegalStateException("No straight leg at " + branchPos));
                        return new Turnout(graph, straight, edge);
                    }
                }
            }
            throw new IllegalStateException("No siding starts at " + branchPos);
        }

        private record Turnout(TrackGraph graph, TrackEdge main, TrackEdge siding) {}

        void restoreTrack() {
            double reduced = westFeederCurrent();
            test.assertTrue(reduced > 0.5 && reduced < completeCurrent - 0.001,
                    "Removing one track edge affected either both routes or neither route");
            editedGraph.putConnection(removedEdge.node1, removedEdge.node2, removedEdge);
            editedGraph.putConnection(removedReverse.node1, removedReverse.node2, removedReverse);
            editedGraph.invalidateBounds();
        }

        void linkRoutes() {
            CatenaryWireChecks.linkVia(test, west, main, east);
            CatenaryWireChecks.linkVia(test, east, siding, west);
            CatenaryWireChecks.rejectDuplicate(test, east, main, west);
        }

        private void source(BlockPos pos, double voltage) {
            var terminals = Terminals.block(pos);
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            var network = grid.prepareForConnection(terminals.supply(), terminals.returnWire());
            var source = new CurrentSourceWire(terminals.supply().getNode(level), terminals.returnWire().getNode(level), 1);
            source.setCurrent(voltage);
            network.addWire(source);
        }

        private double westFeederCurrent() {
            var terminals = Terminals.block(west);
            double voltage = terminals.supply().getNode(level).getVoltage() - terminals.returnWire().getNode(level).getVoltage();
            return SUPPLY - voltage;
        }

        void cutMain() {
            westCurrent = westFeederCurrent();
            test.assertTrue(Math.abs(westCurrent - completeCurrent) < 0.001 && load.current() > 2.2,
                    "Both Power Grid feeders should contribute to the connected passing loop");
            System.out.printf(java.util.Locale.ROOT, "CATENARY_LOOP_TOPOLOGY idleNodes=0 liveNodes=%d restoredWestAmps=%.4f%n",
                    virtualNodes(), westCurrent);
            CatenaryWireChecks.cutFromMenu(test, westRail);
        }

        void cutSiding() {
            var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(west);
            double reduced = westFeederCurrent();
            test.assertTrue(hanger.linkedTo(east) && reduced > 0.5 && reduced < westCurrent - 0.001,
                    "Cutting one route should divert current through the siding. West supply was " + westCurrent + ", then " + reduced);
            CatenaryWireChecks.cutFromMenu(test, westRail);
        }

        void checkEast() {
            var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(west);
            test.assertTrue(!hanger.linkedTo(east) && Math.abs(westFeederCurrent()) < 0.002 && load.current() > 2,
                    "Removing both spans should isolate the west supply while keeping the east load powered");
            unload();
            test.succeed();
        }

        void unload() {
            for (int x = 0; x <= 4; x++) level.setChunkForced(x, rail.getZ() >> 4, false);
        }
    }

    private static final class Drive {
        private static final BlockPos MOTOR = new BlockPos(-3, 0, 0);
        private static final BlockPos ROOF = new BlockPos(2, 4, 0);
        private final Fixture layout;
        private final ServerLevel level;
        private final GameTestHelper test;
        private final BlockPos start;
        private SableTrain train;
        private ServerSubLevel body;
        private PantographBlockEntity pickup;
        private ElectricMotorBlockEntity motor;
        private TransmissionBlockEntity transmission;
        private BearingAxleBlockEntity axle;
        private ElectricWire secondLoad;
        private int leg = -1;
        private int samples;
        private double minimum;
        private double furthestSouth;
        private Couple<TrackNode> turnout;
        private long heldUntil;

        Drive(Fixture layout) {
            this.layout = layout;
            level = layout.level;
            test = layout.test;
            start = layout.rail.east(5).above();
        }

        void build() {
            var frame = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            for (int x = -3; x <= 6; x++) for (int z = -1; z <= 1; z++) {
                if (x >= 0 || z != 0) put(start.offset(x, 1, z), frame);
            }
            for (int x : new int[]{0, 5}) put(start.east(x), AllBlocks.SMALL_BOGEY.getDefaultState()
                    .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
            put(start.west(), state("loconautics:bearing_axle").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
            put(start.west(2), state("loconautics:transmission").setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            put(start.offset(MOTOR), state("powergrid:electric_motor").setValue(BlockStateProperties.FACING, Direction.EAST));
            var front = start.offset(ROOF);
            put(front.below(2), frame);
            put(front.below(), frame);
            var pantograph = state("powerdock:pantograph").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            var raised = (BooleanProperty) pantograph.getBlock().getStateDefinition().getProperty("raised");
            put(front, pantograph.setValue(raised, true));
            // Co-located shoes draw through one tap on each wire while the two pickups remain independent loads.
            put(front.above(), pantograph.setValue(raised, true));
            level.addFreshEntity(new SuperGlueEntity(level, new AABB(Vec3.atLowerCornerOf(start.offset(-3, 0, -2)),
                    Vec3.atLowerCornerOf(start.offset(7, 7, 3)))));
        }

        void assemble() {
            train = SableTrainSpawner.assembleAt(level, start, FORWARD, null, "Passing loop electric train");
            test.assertTrue(train != null && train.car().bogeys().size() == 2, "Could not assemble the electric locomotive");
            body = (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(train.car().subLevelId());
            var center = body.getPlot().getCenterBlock();
            pickup = (PantographBlockEntity) level.getBlockEntity(center.offset(ROOF));
            motor = (ElectricMotorBlockEntity) level.getBlockEntity(center.offset(MOTOR));
            transmission = (TransmissionBlockEntity) level.getBlockEntity(center.west(2));
            axle = (BearingAxleBlockEntity) level.getBlockEntity(center.west());
            test.assertTrue(pickup != null && motor != null && transmission != null && axle != null,
                    "The train lost a pickup or drive component during assembly");
        }

        void energize() {
            wireTrain();
            raise(layout.west);
            raise(layout.east);
            layout.linkRoutes();
            layout.source(layout.west, 210);
            layout.source(layout.east, 210);
        }

        void wireTrain() {
            test.assertTrue(motor.getGeneratedSpeed() == 0, "The motor turned before the line was energized");
            var copper = BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:insulated_copper_wire"));
            for (int pin = 0; pin < 2; pin++) {
                var wire = WireItem.connect(level, new ItemStack(copper, 64), null,
                        new BlockWireEndpoint(pickup.getBlockPos(), pin), new BlockWireEndpoint(motor.getBlockPos(), pin));
                test.assertTrue(wire.getObject() != null, "Could not connect the train motor to its pantograph");
            }
            var testPickup = Terminals.block(pickup.getBlockPos().above());
            secondLoad = GlobalElectricNetworks.getWorldNetworks(level).makeSimpleWire(testPickup.supply(), testPickup.returnWire(), 200);
            test.assertTrue(secondLoad != null, "Could not connect the extra pickup load");
        }

        void raise(BlockPos pos) {
            var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(pos);
            test.assertTrue(hanger != null && hanger.adjust(true), "Could not raise the wire to clear both pickups");
        }

        void start() {
            test.assertTrue(pickup.connected() && Math.abs(motor.getGeneratedSpeed()) > 5, "The overhead line did not start the only motor");
            nextLeg();
        }

        private void nextLeg() {
            leg++;
            minimum = Double.POSITIVE_INFINITY;
            furthestSouth = Double.NEGATIVE_INFINITY;
            samples = 0;
            transmission.setComputerDrive(28, isWestbound());
            train.setBrakeHold(false);
            train.setBrakeHard(false);
            turnout = selectTurnout();
        }

        private Couple<TrackNode> selectTurnout() {
            var fork = layout.turnout(isWestbound());
            var branch = onSiding() ? fork.siding() : fork.main();
            return Couple.create(branch.node1, branch.node2);
        }

        void tick() {
            if (leg < 0 || leg > 3) return;
            var position = position();
            double volts = verifyPower(position);
            if (heldUntil != 0) {
                if (level.getGameTime() >= heldUntil) {
                    heldUntil = 0;
                    nextLeg();
                }
                return;
            }
            minimum = Math.min(minimum, volts);
            samples++;
            if (position.x > 27 && position.x < 33) furthestSouth = Math.max(furthestSouth, position.z - layout.rail.getZ());
            choosePath(turnout);
            if (isWestbound() ? position.x >= 11.0 : position.x <= 54.5) return;
            test.assertTrue(samples > 30 && Double.isFinite(furthestSouth)
                            && (onSiding() ? furthestSouth > 5 : furthestSouth < 2),
                    "The train selected the wrong leg through the turnout. Trip " + leg + ", z offset " + furthestSouth);
            System.out.printf(java.util.Locale.ROOT, "CATENARY_LOOP leg=%d samples=%d minimum=%.3f z=%.3f%n",
                    leg, samples, minimum, furthestSouth);
            if (leg == 3) {
                transmission.setComputerDrive(0, false);
                leg++;
                layout.unload();
                test.succeed();
            } else {
                train.setBrakeHold(true);
                train.setBrakeHard(true);
                transmission.setComputerDrive(0, isWestbound());
                heldUntil = level.getGameTime() + 20;
            }
        }

        private Vec3 position() {
            return body.logicalPose().transformPosition(pickup.getBlockPos().getCenter());
        }

        private double verifyPower(Vec3 position) {
            test.assertTrue(!train.isDerailed(), "The locomotive derailed on leg " + leg + " at " + position);
            var positive = new BlockWireEndpoint(motor.getBlockPos(), 0).getNode(level);
            var negative = new BlockWireEndpoint(motor.getBlockPos(), 1).getNode(level);
            double volts = positive.getVoltage() - negative.getVoltage();
            test.assertTrue(pickup.connected() && volts > 100 && secondLoad.current() > 0.5,
                    "A pickup lost power or reversed its terminals at " + position + " leg=" + leg + " motor=" + volts
                            + " second current=" + secondLoad.current());
            return volts;
        }

        private void choosePath(Couple<TrackNode> branch) {
            if (train.car().carriage() != null) train.car().carriage().setPath(new ArrayList<>(List.of(branch)));
            for (var bogey : train.car().bogeys()) bogey.rail().setPath(new ArrayList<>(List.of(branch)));
        }

        private boolean onSiding() { return leg == 1 || leg == 2; }
        private boolean isWestbound() { return (leg & 1) != 0; }

        private void put(BlockPos pos, BlockState state) { level.setBlockAndUpdate(pos, state); }

        private static BlockState state(String id) {
            return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
        }
    }

    private static final class ReversingLoop {
        private final Fixture layout;
        private final Drive drive;
        private final BlockPos out, turn, back;
        private BlockPos returnCurve;
        private Couple<TrackNode> entrance;
        private boolean departed;
        private boolean rounded;
        private int samples;
        private double minimum = Double.POSITIVE_INFINITY;

        ReversingLoop(Fixture layout) {
            this.layout = layout;
            drive = new Drive(layout);
            out = layout.rail.east(56).above(7);
            turn = layout.rail.east(100).south(15).above(7);
            back = layout.rail.east(70).south(30).above(7);
        }

        void build() {
            force(true);
            var rail = layout.rail;
            for (int x = 1; x <= 86; x++) layout.track(rail.east(x));
            for (int x = 66; x <= 86; x++) layout.track(rail.east(x).south(30));
            for (int z = 14; z <= 16; z++) layout.track(rail.east(100).south(z), TrackShape.ZO);
            var south = new Vec3(0, 0, 1);
            var returning = layout.curve(rail.east(14), rail.east(66).south(30));
            returnCurve = BlockPos.containing(returning.getPosition(0.5)).above(7);
            layout.curve(rail.east(86), rail.east(100).south(14), FORWARD, south.reverse());
            layout.curve(rail.east(100).south(16), rail.east(86).south(30), south, FORWARD);
            var hanger = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger"));
            for (var pos : List.of(layout.west, out, turn, back)) {
                var at = pos.below(7);
                TrackPropagator.onRailAdded(layout.level, at, layout.level.getBlockState(at));
                layout.level.setBlockAndUpdate(pos, hanger.defaultBlockState());
            }
            layout.level.setBlockAndUpdate(returnCurve, hanger.defaultBlockState());
            drive.build();
        }

        void energize() {
            drive.wireTrain();
            var hangers = List.of(layout.west, out, turn, back, returnCurve);
            for (var pos : hangers) drive.raise(pos);
            for (int i = 0; i < hangers.size(); i++) {
                var from = (CatenaryHangerBlockEntity) layout.level.getBlockEntity(hangers.get(i));
                var to = (CatenaryHangerBlockEntity) layout.level.getBlockEntity(hangers.get((i + 1) % hangers.size()));
                if (!from.linkTo(to)) {
                    reportTrack();
                    layout.test.fail("Could not wire the reversing loop from " + from.getBlockPos() + " to " + to.getBlockPos());
                }
            }
            layout.source(layout.west, 210);
            layout.source(turn, 210);
        }

        private void reportTrack() {
            for (var graph : Create.RAILWAYS.trackNetworks.values()) {
                for (var location : graph.getNodes()) {
                    var point = location.getLocation();
                    if (Math.abs(point.z - layout.rail.getZ()) > 36 || point.x < 0 || point.x > 104) continue;
                    var edges = graph.getConnectionsFrom(graph.locateNode(location));
                    if (edges == null) continue;
                    for (var edge : edges.values()) {
                        System.out.printf(java.util.Locale.ROOT, "CATENARY_REVERSING_TRACK %s %s -> %s %.2f%n", graph.id,
                                point, edge.node2.getLocation().getLocation(), edge.getLength());
                    }
                }
            }
        }

        void depart() {
            var straight = layout.turnout(layout.rail.east(14), false).main();
            entrance = Couple.create(straight.node1, straight.node2);
            drive.transmission.setComputerDrive(36, false);
            departed = true;
        }

        void tick() {
            if (!departed) return;
            var pos = drive.position();
            minimum = Math.min(minimum, drive.verifyPower(pos));
            samples++;
            if (!rounded && pos.x < 28) drive.choosePath(entrance);
            if (pos.x > 40 && pos.z > layout.rail.getZ() + 25) rounded = true;
            if (rounded && pos.x < 8 && pos.z < layout.rail.getZ() + 2) {
                drive.transmission.setComputerDrive(0, false);
                System.out.printf(java.util.Locale.ROOT, "CATENARY_REVERSING_LOOP samples=%d minimum=%.3f V%n", samples, minimum);
                force(false);
                departed = false;
                layout.test.succeed();
            }
        }

        private void force(boolean loaded) {
            for (int x = 0; x <= 6; x++) {
                for (int z = layout.rail.getZ() >> 4; z <= (layout.rail.getZ() + 31) >> 4; z++) {
                    layout.level.setChunkForced(x, z, loaded);
                }
            }
        }
    }
}
