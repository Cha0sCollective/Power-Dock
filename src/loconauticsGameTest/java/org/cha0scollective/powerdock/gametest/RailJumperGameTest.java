package org.cha0scollective.powerdock.gametest;

import com.mojang.authlib.GameProfile;
import com.lycoris.loconautics.content.knuckle.KnuckleBlockEntity;
import com.lycoris.loconautics.content.knuckle.KnuckleConnectionManager;
import com.lycoris.loconautics.trainphysics.SableTrain;
import com.lycoris.loconautics.trainphysics.drive.TrainRailAdvance;
import com.lycoris.loconautics.trainphysics.spawn.SableTrainSpawner;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
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
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireConnection;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.cha0scollective.powerdock.PantographBlockEntity;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;

@GameTestHolder("powerdock_loconautics_tests")
@PrefixGameTestTemplate(false)
public final class RailJumperGameTest {
    private static final int TRACK_Y = 66;
    private static final int BASE_Y = TRACK_Y + 1;
    private static final int START_Z = 104;
    private static final BlockPos CURVE_START = new BlockPos(50, TRACK_Y, START_Z);
    private static final BlockPos CURVE_END = new BlockPos(66, TRACK_Y, 120);
    private static final int GRADE_START = 134;
    private static final int GRADE_END = 159;
    private static final int TOP_Y = TRACK_Y + 5;
    private static final Vec3 EAST = new Vec3(1, 0, 0);
    private static final Vec3 NORTH = new Vec3(0, 0, -1);
    private static final BlockPos ROOF_PICKUP = new BlockPos(2, 4, 0);
    private static final BlockPos CAB_LINK = new BlockPos(1, 2, 1);
    private static final BlockPos FIRST_FEEDER = new BlockPos(7, TRACK_Y + 7, START_Z);
    private static final BlockPos APPROACH_HANGER = new BlockPos(45, TRACK_Y + 7, START_Z);
    private static final BlockPos SECOND_FEEDER = new BlockPos(66, TOP_Y + 7, 191);
    private static final BlockPos SECOND_END = new BlockPos(66, TOP_Y + 7, 202);

    @GameTest(template = "empty", timeoutTicks = 1250)
    public static void railJumpersStayPoweredOnCurveAndGradeThenBreakAway(GameTestHelper test) {
        var run = new Run(test);
        run.build();
        test.runAtTickTime(10, () -> PantographReceiverChecks.bind(test, startAt(0).offset(ROOF_PICKUP)));
        test.runAtTickTime(20, run::assemble);
        test.runAtTickTime(60, run::wire);
        test.runAtTickTime(75, run::fitKnuckles);
        test.runAtTickTime(85, run::verifyCabBinding);
        test.runAtTickTime(95, () -> run.setCabPower(true));
        test.runAtTickTime(105, run::overrideCabPower);
        test.runAtTickTime(115, run::verifyManualOverride);
        test.runAtTickTime(125, () -> run.setCabPower(true));
        test.runAtTickTime(140, run::startRun);
        test.onEachTick(run::tick);
    }

    private static final class Run {
        final GameTestHelper test;
        final ServerLevel level;
        final Car[] cars = new Car[3];
        final int[] curve = new int[3];
        final int[] grade = new int[3];
        List<BlockPos> hangers;
        Vec3 curveAim, gradeAim;
        final boolean withComputerCraft = ModList.get().isLoaded("computercraft");
        RailJumperDataTest data;
        ElectricWire load;
        ElectricWire otherCornerLoad;
        ElectricWire overheadLoad;
        long start = Long.MAX_VALUE;
        long release = Long.MAX_VALUE;
        long cleanup = Long.MAX_VALUE;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        double initialVolts;
        int lowTicks;
        int overheadLowTicks;
        double minimumOverhead = Double.POSITIVE_INFINITY;
        boolean checkingNeutralGap;
        int neutralTicks;
        int samples;

        Run(GameTestHelper test) {
            this.test = test;
            this.level = test.getLevel();
        }

        void build() {
            for (int x = 0; x <= 4; x++) for (int z = 6; z <= 12; z++) level.setChunkForced(x, z, true);
            for (int x = 3; x <= CURVE_START.getX(); x++) track(new BlockPos(x, TRACK_Y, START_Z), TrackShape.XO);
            for (int z = CURVE_END.getZ(); z <= GRADE_START; z++) track(new BlockPos(CURVE_END.getX(), TRACK_Y, z), TrackShape.ZO);
            for (int z = GRADE_END; z < 206; z++) track(new BlockPos(CURVE_END.getX(), TOP_Y, z), TrackShape.ZO);
            var bend = bezier(CURVE_START, CURVE_END, EAST, NORTH, "curve");
            var climb = bezier(new BlockPos(CURVE_END.getX(), TRACK_Y, GRADE_START), new BlockPos(CURVE_END.getX(), TOP_Y, GRADE_END),
                    new Vec3(0, 0, 1), NORTH, "grade");
            curveAim = bend.getPosition(0.34);
            gradeAim = climb.getPosition(0.69);
            var bendHanger = BlockPos.containing(bend.getPosition(0.5).add(0, 7, 0));
            var climbHanger = BlockPos.containing(climb.getPosition(0.5).add(0, 7, 0));
            for (var hanger : new BlockPos[]{bendHanger, climbHanger}) {
                test.assertTrue(!(level.getBlockState(hanger.below(7)).getBlock() instanceof ITrackBlock),
                        "Catenary support must test the middle of a Bézier connection");
            }
            hangers = List.of(FIRST_FEEDER, APPROACH_HANGER, bendHanger, new BlockPos(66, TRACK_Y + 7, 126),
                    climbHanger, new BlockPos(66, TOP_Y + 7, 170), new BlockPos(66, TOP_Y + 7, 187));
            for (var pos : hangers) level.setBlockAndUpdate(pos, block("powerdock:catenary_hanger"));
            level.setBlockAndUpdate(SECOND_FEEDER, block("powerdock:catenary_hanger"));
            level.setBlockAndUpdate(SECOND_END, block("powerdock:catenary_hanger"));
            for (int index = 0; index < cars.length; index++) {
                var p = startAt(index);
                for (int x = -1; x <= 6; x++) {
                    for (int z = -2; z <= 2; z++) {
                        level.setBlockAndUpdate(p.above().offset(x,0,z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                    }
                }
                for (int bogey : new int[]{0, 5}) {
                    level.setBlockAndUpdate(p.east(bogey), AllBlocks.SMALL_BOGEY.getDefaultState()
                            .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
                }
                fitJumper(p, index, Direction.WEST, false);
                fitJumper(p, index, Direction.EAST, false);
                if (index == 0) {
                    // Put the pickup five blocks above the rail on the five-wide locomotive.
                    var mount = p.offset(ROOF_PICKUP);
                    level.setBlockAndUpdate(mount.below(2), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                    level.setBlockAndUpdate(mount.below(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                    var roof = block("powerdock:pantograph");
                    var raised = (BooleanProperty) roof.getBlock().getStateDefinition().getProperty("raised");
                    level.setBlockAndUpdate(mount, roof.setValue(raised, true)
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
                    level.setBlockAndUpdate(p.offset(CAB_LINK), AllBlocks.REDSTONE_LINK.getDefaultState()
                            .setValue(RedstoneLinkBlock.FACING, Direction.UP));
                }
                if (index == 1) fitJumper(p, index, Direction.WEST, true);
                if (index != 0) fitJumper(p, index, Direction.EAST, true);
                if (withComputerCraft) {
                    var modem = block("computercraft:wired_modem_full");
                    if (index == 1) {
                        var cable = block("computercraft:cable");
                        var hasCable = (BooleanProperty) cable.getBlock().getStateDefinition().getProperty("cable");
                        cable = cable.setValue(hasCable, true);
                        for (int x = 0; x <= 5; x++) level.setBlockAndUpdate(p.offset(x, 2, -2), cable);
                    } else if (index == 0) {
                        level.setBlockAndUpdate(jumperAt(p, index, Direction.WEST, false).above(), modem);
                    } else {
                        level.setBlockAndUpdate(jumperAt(p, index, Direction.EAST, false).west(), modem);
                    }
                }
                level.addFreshEntity(new SuperGlueEntity(level, new AABB(Vec3.atLowerCornerOf(p.west().north(3)),
                        Vec3.atLowerCornerOf(p.east(7).above(5).south(4)))));
            }
        }

        private void fitJumper(BlockPos anchor, int car, Direction outward, boolean otherCorner) {
            var socket = jumperAt(anchor, car, outward, otherCorner);
            var wall = car != 0 && !otherCorner;
            level.setBlockAndUpdate(socket.below(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            if (wall) {
                level.setBlockAndUpdate(socket.north(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                level.setBlockAndUpdate(socket.south(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                level.setBlockAndUpdate(socket.above(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            }
            level.setBlockAndUpdate(socket, face(socketBlock(wall).defaultBlockState(), outward));
        }

        private void track(BlockPos pos, TrackShape shape) {
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, shape));
        }

        private BezierConnection bezier(BlockPos from, BlockPos to, Vec3 enter, Vec3 exit, String name) {
            var a = level.getBlockState(from).setValue(TrackBlock.HAS_BE, true);
            var b = level.getBlockState(to).setValue(TrackBlock.HAS_BE, true);
            level.setBlockAndUpdate(from, a);
            level.setBlockAndUpdate(to, b);
            var track = (ITrackBlock) a.getBlock();
            var curve = new BezierConnection(Couple.create(from, to),
                    Couple.create(track.getCurveStart(level, from, a, enter), track.getCurveStart(level, to, b, exit)),
                    Couple.create(enter, exit), Couple.create(new Vec3(0, 1, 0), new Vec3(0, 1, 0)),
                    true, false, track.getMaterial());
            ((TrackBlockEntity) level.getBlockEntity(from)).addConnection(curve);
            ((TrackBlockEntity) level.getBlockEntity(to)).addConnection(curve.secondary());
            System.out.printf(Locale.ROOT, "RAIL_JUMPER_LAYOUT %sLength=%.3f rise=%d%n",
                    name, curve.getLength(), to.getY()-from.getY());
            return curve;
        }

        void assemble() {
            var container = SubLevelContainer.getContainer(level);
            KnuckleConnectionManager.watch(container);
            for (int index = 0; index < cars.length; index++) {
                var anchor = startAt(index);
                TrackPropagator.onRailAdded(level, anchor.below(), level.getBlockState(anchor.below()));
                var train = SableTrainSpawner.assembleAt(level, anchor, EAST, null, "Jumper car " + (index + 1));
                test.assertTrue(train != null, "Loconautics could not assemble car " + index);
                test.assertTrue(train.car().bogeys().size() == 2, "Car " + index + " had " + train.car().bogeys().size() + " seated bogeys");
                var body = (ServerSubLevel) container.getSubLevel(train.car().subLevelId());
                cars[index] = new Car(body, train, body.getPlot().getCenterBlock(), index);
                test.assertTrue(level.getBlockState(cars[index].jumper(Direction.WEST)).is(socketBlock(index != 0)),
                        "The Rail Jumper was not assembled");
                if (index != 0) {
                    test.assertTrue(level.getBlockState(cars[index].otherCorner(Direction.EAST)).is(socketBlock(false)),
                            "The opposite-corner Rail Jumper was not assembled");
                }
                System.out.println("RAIL_JUMPER_BUILT car=" + index + " pose=" + cars[index].position());
            }
        }

        void fitKnuckles() {
            test.assertTrue(Math.abs(load.potentialDifference()) < 0.01 && !connected(cars[0].jumper(Direction.WEST)),
                    "Neighboring carriages connected before the knuckles coupled");
            if (withComputerCraft) {
                data = new RailJumperDataTest(level, cars[0].jumper(Direction.WEST),
                        cars[1].jumper(Direction.EAST), cars[2].jumper(Direction.EAST));
                test.assertTrue(!data.canReachTail(), "Adjacent uncoupled carriages shared a wired network");
            }
            // Fit the knuckles after all the separate carriage bodies have settled onto their rails.
            var knuckle = block("loconautics:knuckle");
            for (var car : cars) {
                System.out.println("RAIL_JUMPER_SETTLED pose=" + car.position() + " derail=" + car.train().isDerailed());
                test.assertTrue(!car.train().isDerailed(), "A carriage lost its rail during setup");
                for (var facing : new Direction[]{Direction.WEST, Direction.EAST}) {
                    var position = car.knucklePos(facing);
                    level.setBlockAndUpdate(position, knuckle.setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
                }
            }
        }

        void wire() {
            var recipe = level.getRecipeManager().byKey(ResourceLocation.parse("powerdock:rail_jumper")).orElseThrow();
            var result = recipe.value().getResultItem(level.registryAccess());
            test.assertTrue(result.is(socketBlock(false).asItem()) && result.getCount() == 2, "Deck Rail Jumper recipe is missing");
            var wallRecipe = level.getRecipeManager().byKey(ResourceLocation.parse("powerdock:flush_rail_jumper")).orElseThrow();
            var wallResult = wallRecipe.value().getResultItem(level.registryAccess());
            test.assertTrue(wallResult.is(socketBlock(true).asItem()) && wallResult.getCount() == 1,
                    "Flush Rail Jumper recipe is missing");
            for (var facing : Direction.Plane.HORIZONTAL) {
                checkPowerPosts(cars[0].jumper(Direction.WEST), false, facing);
                checkPowerPosts(cars[1].jumper(Direction.EAST), true, facing);
            }
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            for (int pin = 0; pin < 2; pin++) {
                grid.makeSimpleWire(cars[1].terminal(Direction.WEST, pin), cars[1].terminal(Direction.EAST, pin), 0.02f);
            }
            var frontPositive = cars[0].terminal(Direction.WEST, 0);
            var frontReturn = cars[0].terminal(Direction.WEST, 1);
            var circuit = grid.prepareForConnection(frontPositive, frontReturn);
            var source = new CurrentSourceWire(frontPositive.getNode(level), frontReturn.getNode(level), 1);
            source.setCurrent(24);
            circuit.addWire(source);
            load = grid.makeSimpleWire(cars[2].terminal(Direction.EAST, 0), cars[2].terminal(Direction.EAST, 1), 20f);

            var otherSourcePositive = new BlockWireEndpoint(cars[1].otherCorner(Direction.WEST), 0);
            var otherSourceReturn = new BlockWireEndpoint(cars[1].otherCorner(Direction.WEST), 1);
            var otherCircuit = grid.prepareForConnection(otherSourcePositive, otherSourceReturn);
            var otherSource = new CurrentSourceWire(otherSourcePositive.getNode(level), otherSourceReturn.getNode(level), 1);
            otherSource.setCurrent(9);
            otherCircuit.addWire(otherSource);
            otherCornerLoad = grid.makeSimpleWire(new BlockWireEndpoint(cars[2].otherCorner(Direction.EAST), 0),
                    new BlockWireEndpoint(cars[2].otherCorner(Direction.EAST), 1), 20f);
            test.assertTrue(otherCornerLoad != null, "The opposite-corner load could not attach to its power posts");
            wireOverhead(grid);
        }

        private void wireOverhead(org.patryk3211.powergrid.electricity.WorldNetworks grid) {
            for (var name : new String[]{"catenary_hanger", "pantograph"}) {
                test.assertTrue(level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("powerdock", name)).isPresent(),
                        "Missing catenary recipe: " + name);
            }
            verifyHangerHeights(FIRST_FEEDER);
            verifyHangerHeights(hangers.get(2));
            verifyRailPlacement("straight", new Vec3(12.5, TRACK_Y + 0.125, START_Z + 0.5), false);
            verifyRailPlacement("curve", curveAim, true);
            verifyRailPlacement("incline", gradeAim, true);
            for (int i = 1; i < hangers.size(); i++) {
                var next = (CatenaryHangerBlockEntity) level.getBlockEntity(hangers.get(i));
                if (i == 2) {
                    next.adjust(false);
                    next.adjust(true);
                    test.assertTrue(next.width() == 9 && next.height() == 8, "Clearance settings did not adjust independently");
                }
                CatenaryWireChecks.link(test, hangers.get(i - 1), hangers.get(i), false);
            }
            CatenaryWireChecks.link(test, SECOND_FEEDER, SECOND_END, true);
            System.out.println("CATENARY_COPPER_WIRE preview, confirm, and unlink passed");
            energize(grid, FIRST_FEEDER, 30);
            energize(grid, SECOND_FEEDER, 42);
            var pickup = cars[0].center().offset(ROOF_PICKUP);
            test.assertTrue(level.getBlockEntity(pickup) instanceof PantographBlockEntity, "Roof pickup was not assembled");
            overheadLoad = grid.makeSimpleWire(new BlockWireEndpoint(pickup, 0), new BlockWireEndpoint(pickup, 1), 20);
            test.assertTrue(overheadLoad != null, "Roof pickup load failed to join its terminals");
        }

        private void verifyHangerHeights(BlockPos pos) {
            var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(pos);
            var original = hanger.fixture();
            test.assertTrue(original != null, "Hanger did not snap to the track at " + pos);
            int originalHeight = hanger.height();
            for (int i = 0; i < 9; i++) {
                hanger.adjust(true);
                var fixture = hanger.fixture();
                var beam = fixture.crossbeamCenter();
                double rise = hanger.height() - originalHeight;
                test.assertTrue(Math.abs(beam.y - original.crossbeamCenter().y - rise) < 1e-6
                                && Math.abs(fixture.housing().y - original.housing().y - rise) < 1e-6
                                && fixture.railY() == original.railY(),
                        "The housing and crossbeam did not move together at " + pos);
                for (int side : new int[]{-1, 1}) {
                    var wire = fixture.insulatorBottom(side);
                    var mount = fixture.insulatorTop(beam, side);
                    test.assertTrue(wire.x == mount.x && wire.z == mount.z && mount.y == beam.y && mount.y > wire.y,
                            "A ceramic insulator did not hang between its wire and the crossbeam at " + pos);
                }
            }
            test.assertTrue(hanger.height() == originalHeight, "The height cycle did not wrap at " + pos);
            System.out.println("CATENARY_HEIGHT crossbeam, masts and ceramic insulators checked at " + pos);
        }

        private void verifyRailPlacement(String section, Vec3 aim, boolean curved) {
            var rail = BlockPos.containing(aim);
            var expected = BlockPos.containing(aim.add(0, 4.625, 0));
            var railState = level.getBlockState(rail);
            var ground = BlockPos.betweenClosedStream(rail.offset(-1, -1, -1), rail.offset(1, 2, 1))
                    .map(BlockPos::immutable).collect(Collectors.toMap(pos -> pos, level::getBlockState));
            test.assertTrue(!curved || !(railState.getBlock() instanceof ITrackBlock), "The test needs the middle of the " + section);
            var player = test.makeMockPlayer(GameType.CREATIVE);
            player.setPos(aim.x - 1.5, aim.y, aim.z);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
            var item = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powerdock:catenary_hanger")));
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            var railHit = new BlockHitResult(aim, Direction.UP, rail, false);
            var result = curved ? item.getItem().use(level, player, InteractionHand.MAIN_HAND).getResult()
                    : item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, railHit));
            test.assertTrue(result.consumesAction(), "The hanger could not be placed above the selected " + section + ": " + result);
            var hanger = BlockPos.betweenClosedStream(expected.offset(-1, -1, -1), expected.offset(1, 1, 1))
                    .map(level::getBlockEntity).filter(CatenaryHangerBlockEntity.class::isInstance)
                    .map(CatenaryHangerBlockEntity.class::cast).findFirst().orElse(null);
            test.assertTrue(hanger != null, "Placement did not put a hanger above the targeted " + section + " near " + expected);
            // Normal interaction happens after the new hanger has registered in the spatial index.
            hanger.tick();
            var anchor = hanger.getBlockPos();
            var oldHead = hanger.headPosition();
            test.assertTrue(hanger.height() == 4 && oldHead.equals(anchor), "The hanger did not start at four blocks");
            var fixture = hanger.fixture();
            var mountedRail = Vec3.atLowerCornerOf(anchor).add(fixture.wireCenter().x, fixture.railY(), fixture.wireCenter().z);
            test.assertTrue(aim.distanceToSqr(mountedRail) < 0.75 * 0.75, "The hanger snapped away from the aimed rail at " + aim);
            verifyHangerAlignment(section, hanger);
            test.assertTrue(ground.entrySet().stream().allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue())),
                    "Placement changed the track or added a control block over it");

            var wireItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:wire"));
            var lead = new ItemStack(wireItem, 32);
            var endpoint = new BlockWireEndpoint(anchor, 0);
            var first = endpoint.getExactPosition(level);
            var remote = new BlockWireEndpoint(FIRST_FEEDER, 0);
            if (!curved) {
                player.setItemInHand(InteractionHand.MAIN_HAND, lead);
                var wireContext = terminalContext(player, oldHead, first);
                test.assertTrue(IElectric.makeHangingWireConnection(level, endpoint, remote, wireContext).consumesAction()
                                && hanger.getElectricBehaviour().hasConnection(endpoint, remote),
                        "Power Grid could not attach a hanging wire to the head");
            }

            var editor = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "HangerTest"),
                    ClientInformation.createDefault()) {
                @Override public OptionalInt openMenu(MenuProvider provider) {
                    containerMenu = provider.createMenu(1, getInventory(), this);
                    return OptionalInt.of(1);
                }
                @Override public void displayClientMessage(net.minecraft.network.chat.Component text, boolean overlay) {}
            };
            editor.setPos(player.position());
            editor.setShiftKeyDown(true);
            // Curves highlight the saved mount's section of track after placement.
            var look = (curved ? mountedRail : aim).subtract(editor.getEyePosition());
            editor.setYRot((float) Math.toDegrees(Math.atan2(look.z, look.x)) - 90);
            editor.setYHeadRot(editor.getYRot());
            editor.setXRot((float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance())));
            boolean handled;
            if (curved) {
                handled = openCurvedRailSettings(editor);
            } else {
                var use = new PlayerInteractEvent.RightClickBlock(editor, InteractionHand.MAIN_HAND, rail, railHit);
                NeoForge.EVENT_BUS.post(use);
                handled = use.isCanceled();
            }
            var seen = editor.pick(editor.blockInteractionRange(), 1, false);
            test.assertTrue(handled && editor.containerMenu != editor.inventoryMenu
                            && ResourceLocation.parse("powerdock:hanger_settings").equals(BuiltInRegistries.MENU.getKey(editor.containerMenu.getType())),
                    "Sneak-right-click did not open hanger settings over the " + section
                            + ": handled=" + handled + ", hit=" + seen.getType() + " " + seen.getLocation()
                            + ", sneak=" + editor.isShiftKeyDown() + ", mounted=" + (hanger.fixture() != null));
            var menu = editor.containerMenu;
            test.assertTrue(menu.clickMenuButton(editor, 1) && menu.clickMenuButton(editor, 3), "The settings did not accept adjustment");
            var movedHead = hanger.headPosition();
            test.assertTrue(hanger.height() == 5 && hanger.width() == 9 && movedHead.equals(oldHead.above())
                            && level.getBlockState(anchor).getShape(level, anchor).isEmpty()
                            && endpoint.getExactPosition(level).distanceToSqr(first.add(0, 1, 0)) < 1e-8
                            && (curved || hanger.getElectricBehaviour().hasConnection(endpoint, remote)),
                    "Raising the assembly did not move the actual Power Grid terminal");
            test.assertTrue(ground.entrySet().stream().allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue())),
                    "Adjusting the hanger changed the track or added a ground block");

            player.setItemInHand(InteractionHand.MAIN_HAND, lead);
            var movedContext = terminalContext(player, movedHead, endpoint.getExactPosition(level));
            var used = IElectric.getAt(level, movedHead).onWire(level.getBlockState(movedHead), movedContext);
            var connectionType = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("powergrid:connection"));
            test.assertTrue(used.consumesAction() && lead.get(connectionType) instanceof WireConnection connection
                            && endpoint.equals(connection.endpoint()),
                    "Power Grid did not select the moved terminal over the " + section + ": " + used);
            verifyHangerAlignment(section + " raised", hanger);

            level.setBlockAndUpdate(movedHead.above(), Blocks.STONE.defaultBlockState());
            menu.clickMenuButton(editor, 1);
            test.assertTrue(hanger.height() == 5 && movedHead.equals(hanger.headPosition())
                            && level.getBlockState(movedHead.above()).is(Blocks.STONE),
                    "Adjusting the hanger overwrote an occupied position");
            level.removeBlock(movedHead.above(), false);
            level.removeBlock(movedHead, false);
            test.assertTrue(level.getBlockState(anchor).isAir() && level.getBlockState(movedHead).isAir(),
                    "Removing the raised head orphaned the control");
            System.out.println("CATENARY_RAIL_AIM " + section + " placement, menu, terminal picking and cleanup passed");
        }

        private static boolean openCurvedRailSettings(ServerPlayer editor) {
            // A Create curve is a vanilla ray miss. The client sends the rail packet instead of a block-use event.
            try {
                var controls = Class.forName("org.cha0scollective.powerdock.HangerRailControls");
                var open = controls.getDeclaredMethod("openAtRail", ServerPlayer.class, boolean.class);
                open.setAccessible(true);
                return (boolean) open.invoke(null, editor, true);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not invoke the curved rail packet handler", failure);
            }
        }

        private UseOnContext terminalContext(net.minecraft.world.entity.player.Player player, BlockPos head, Vec3 contact) {
            var hit = level.clip(new ClipContext(contact.add(0, 1, 0), contact.add(0, -0.5, 0),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            test.assertTrue(hit.getBlockPos().equals(head), "Normal ray picking missed the hanger terminal at " + head);
            return new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        }

        private void verifyHangerAlignment(String section, CatenaryHangerBlockEntity hanger) {
            var fixture = hanger.fixture();
            var railDirection = fixture.contactSpacing().cross(new Vec3(0, 1, 0)).normalize();
            var offset = fixture.housing().subtract(fixture.wireCenter());
            var first = new BlockWireEndpoint(hanger.getBlockPos(), 0).getExactPosition(level);
            var second = new BlockWireEndpoint(hanger.getBlockPos(), 1).getExactPosition(level);
            double square = Math.abs(railDirection.dot(second.subtract(first).normalize()));
            test.assertTrue(square < 1e-3, "The feeder terminals were out of square with the ceramic mounts over the " + section);
            var head = hanger.headPosition();
            var electric = IElectric.getAt(level, head);
            for (int i = 0; i < 2; i++) {
                var post = (TerminalBoundingBox) electric.terminal(level.getBlockState(head), i);
                var outline = post.getOutline();
                test.assertTrue(outline.minX >= 0 && outline.maxX < 1 && outline.minZ >= 0 && outline.maxZ < 1,
                        "The adjusted feeder terminal extends outside its selectable block over the " + section);
            }
            System.out.printf(Locale.ROOT, "CATENARY_SQUARE %s foreAft=%.5f across=%.5f angleError=%.5f%n",
                    section, offset.dot(railDirection), offset.dot(fixture.contactSpacing().normalize()), square);
        }

        private void energize(org.patryk3211.powergrid.electricity.WorldNetworks grid, BlockPos feeder, int voltage) {
            var positive = new BlockWireEndpoint(feeder, 0);
            var negative = new BlockWireEndpoint(feeder, 1);
            var circuit = grid.prepareForConnection(positive, negative);
            var source = new CurrentSourceWire(positive.getNode(level), negative.getNode(level), 1);
            source.setCurrent(voltage);
            circuit.addWire(source);
        }

        void checkPowerPosts(BlockPos pos, boolean wall, Direction outward) {
            var state = face(socketBlock(wall).defaultBlockState(), outward);
            var block = (IElectric) state.getBlock();
            var approach = Vec3.atLowerCornerOf((wall ? outward.getOpposite() : Direction.UP).getNormal());
            var origin = Vec3.atLowerCornerOf(pos);
            for (int pin = 0; pin < 2; pin++) {
                var terminal = block.terminal(state, pin).getOrigin().add(origin);
                var hit = state.getShape(level, pos).clip(terminal.add(approach), terminal.subtract(approach), pos);
                test.assertTrue(hit != null && block.terminalIndexAt(state, hit.getLocation().subtract(origin)) == pin,
                        "A Power Grid wire could not target one of the jumper's copper posts");
            }
        }

        void verifyCabBinding() {
            var pos = cars[0].center().offset(ROOF_PICKUP);
            PantographReceiverChecks.verifyOnTrain(test, pos, cars[0].body.logicalPose().transformPosition(pos.getCenter()));
            test.assertTrue(Math.abs(overheadLoad.potentialDifference()) < 0.01, "An unpowered receiver left the pickup raised");
            var link = BlockEntityBehaviour.get(level, cars[0].center().offset(CAB_LINK), LinkBehaviour.TYPE);
            test.assertTrue(link != null, "The cab Redstone Link was not assembled");
            link.setFrequency(true, new ItemStack(Items.COPPER_INGOT));
            link.setFrequency(false, new ItemStack(Items.REDSTONE));
        }

        void setCabPower(boolean powered) {
            var pos = cars[0].center().offset(CAB_LINK);
            level.setBlockAndUpdate(pos.south(), (powered ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
        }

        void overrideCabPower() {
            test.assertTrue(Math.abs(overheadLoad.potentialDifference()) > 25, "The cab link did not raise and connect the pantograph");
            PantographReceiverChecks.toggleWithEmptyHand(test, cars[0].center().offset(ROOF_PICKUP));
        }

        void verifyManualOverride() {
            test.assertTrue(Math.abs(overheadLoad.potentialDifference()) < 0.01, "Unchanged wireless power undid the manual toggle");
            setCabPower(false);
        }

        void startRun() {
            verifyHangerJoints();
            for (int i = 0; i < cars.length - 1; i++) {
                test.assertTrue(cars[i].coupled(level, cars[i+1]), "Loconautics did not couple neighboring carriages");
            }
            initialVolts = Math.abs(load.potentialDifference());
            System.out.printf(Locale.ROOT, "RAIL_JUMPER_BASELINE %.5f%n", initialVolts);
            test.assertTrue(initialVolts > 20 && bothJumpersConnected(),
                    "The parked three-car train did not pass power through both Rail Jumpers");
            test.assertTrue(bothCornersMatched(), "The parked jumpers crossed corners or used an unmatched socket");
            test.assertTrue(Math.abs(overheadLoad.potentialDifference()) > 25, "Parked pantograph never picked up both wires");
            System.out.println("CATENARY_RECEIVER menu binding survived assembly, cab link and manual override worked");
            if (data != null) test.assertTrue(data.canReachTail(), "The parked train did not pass CC packets both ways");
            start = level.getGameTime() + 1;
        }

        private void verifyHangerJoints() {
            for (int i = 2; i < hangers.size(); i++) {
                var joint = hangers.get(i - 1);
                var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(joint);
                var ends = hanger.overheadWireEnds();
                test.assertTrue(ends.size() == 4 && ends.get(0).distanceToSqr(ends.get(2)) < 1e-6
                                && ends.get(1).distanceToSqr(ends.get(3)) < 1e-6,
                        "The two wire pairs do not meet at " + joint);
            }
            verifyHangerAlignment("linked curve", (CatenaryHangerBlockEntity) level.getBlockEntity(hangers.get(2)));
            verifyHangerAlignment("linked incline", (CatenaryHangerBlockEntity) level.getBlockEntity(hangers.get(4)));
            System.out.println("CATENARY_SUPPORTS wire joints meet at the curve and grade hangers");
        }

        void tick() {
            if (level.getGameTime() < start) return;
            if (cleanup != Long.MAX_VALUE) {
                if (level.getGameTime() >= cleanup) {
                    for (int x = 0; x <= 4; x++) for (int z = 6; z <= 12; z++) level.setChunkForced(x, z, false);
                    test.succeed();
                }
                return;
            }
            if (release != Long.MAX_VALUE) {
                if (level.getGameTime() == release) verifyBreakaway();
                return;
            }
            samples++;
            driveTrain();
            double volts = Math.abs(load.potentialDifference());
            double overheadVolts = Math.abs(overheadLoad.potentialDifference());
            if (checkingNeutralGap) {
                checkNeutralGap(overheadVolts);
                return;
            }
            minimumOverhead = Math.min(minimumOverhead, overheadVolts);
            if (overheadVolts < 25 && ++overheadLowTicks == 1) {
                System.out.printf(Locale.ROOT, "CATENARY_DROPOUT tick=%d car=%s volts=%.5f%n",
                        samples, cars[0].position(), overheadVolts);
            }
            minimum = Math.min(minimum, volts);
            maximum = Math.max(maximum, volts);
            if (volts < 20 || !bothJumpersConnected() || !bothCornersMatched() || (data != null && !data.canReachTail())) {
                lowTicks++;
                if (lowTicks == 1) log("POWER_DROPOUT", volts);
            }
            if (samples % 25 == 0) log("RUN", volts);
            var tail = cars[2].position();
            if (tail.z >= GRADE_END + 2 && tail.y >= TOP_Y) {
                log("FINISHED", volts);
                test.assertTrue(lowTicks == 0, "The last carriage lost power or CC data for " + lowTicks + " sampled ticks");
                test.assertTrue(overheadLowTicks == 0, "Pantograph lost contact for " + overheadLowTicks + " sampled ticks");
                System.out.printf(Locale.ROOT, "CATENARY_POWERED samples=%d minimum=%.5f%n", samples, minimumOverhead);
                System.out.println("RAIL_JUMPER_CORNERS one offset pair, two independent rear pairs, spare socket stayed open");
                if (data != null) System.out.println("RAIL_JUMPER_DATA continuous bidirectional wired packets through both jumpers");
                for (int i = 0; i < cars.length; i++) {
                    test.assertTrue(curve[i] > 0 && grade[i] > 0, "Car " + (i+1) + " did not cross the curve and grade");
                }
                checkingNeutralGap = true;
            }
        }

        private void driveTrain() {
            var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
            for (int i = 0; i < cars.length; i++) {
                var c = cars[i];
                test.assertTrue(!c.train().isDerailed() && (i == cars.length - 1 || c.coupled(level, cars[i+1])),
                        "The test consist lost its rail or knuckle coupling on sample " + samples);
                var direction = TrainRailAdvance.railTangentOf(c.train().car().bogeys().getFirst()).normalize();
                if (direction.x + direction.z < 0) direction.negate();
                if (Math.abs(direction.x) > 0.15 && Math.abs(direction.z) > 0.15) curve[i]++;
                if (Math.abs(direction.y) > 0.08) grade[i]++;
                var speed = RigidBodyHandle.of(c.body()).getLinearVelocity(new Vector3d());
                double drive = Math.clamp(4.0 - speed.dot(direction), 0, Math.abs(direction.y) > 0.1 ? 0.8 : 0.4);
                pipeline.addLinearAndAngularVelocity(c.body(), direction.mul(drive), new Vector3d());
            }
        }

        private void checkNeutralGap(double volts) {
            for (int terminal = 0; terminal < 2; terminal++) {
                var first = new BlockWireEndpoint(FIRST_FEEDER, terminal).getNode(level);
                var second = new BlockWireEndpoint(SECOND_FEEDER, terminal).getNode(level);
                test.assertTrue(first.getNetwork() != second.getNetwork(), "The pantograph bridged two feeders");
            }
            if (volts < 0.01) neutralTicks++;
            if (volts < 36) return;
            test.assertTrue(neutralTicks >= 2, "The pickup did not open both wire contacts at the insulated gap");
            System.out.printf(Locale.ROOT, "CATENARY_NEUTRAL offTicks=%d secondFeeder=%.5f V%n", neutralTicks, volts);
            setCabPower(false);
            var rearKnuckle = cars[1].knucklePos(Direction.WEST);
            level.setBlockAndUpdate(rearKnuckle.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            KnuckleConnectionManager.disconnect(level, rearKnuckle);
            release = level.getGameTime() + 8;
        }

        void verifyBreakaway() {
            test.assertTrue(Math.abs(overheadLoad.potentialDifference()) < 0.01, "Lowered pantograph still supplies power");
            System.out.println("CATENARY_LOWERED isolated both conductors");
            var mid = cars[1].jumper(Direction.WEST);
            var tail = cars[2].jumper(Direction.EAST);
            test.assertTrue(cars[1].position().distanceTo(cars[2].position()) < 12,
                    "The disconnected cars moved too far to verify coupling-controlled release");
            test.assertTrue(!connected(mid) && !connected(tail) && Math.abs(load.potentialDifference()) < 0.01,
                    "Opening the knuckles left the last car powered");
            test.assertTrue(!connected(cars[1].otherCorner(Direction.WEST)) && !connected(cars[2].otherCorner(Direction.EAST))
                            && Math.abs(otherCornerLoad.potentialDifference()) < 0.01,
                    "Opening the same knuckle left the opposite corner powered");
            test.assertTrue(connected(cars[0].jumper(Direction.WEST)) && connected(cars[1].jumper(Direction.EAST)),
                    "The rear breakaway also opened the still-coupled front jumper");
            if (data != null) {
                test.assertTrue(!data.canReachTail() && data.canReachMiddle(),
                        "The wired network did not split at the open knuckles");
                data.close();
                System.out.println("RAIL_JUMPER_DATA split at rear pair, front pair still carries packets");
            }
            System.out.printf(Locale.ROOT, "RAIL_JUMPER_BREAKAWAY voltage=%.5f carDistance=%.3f%n",
                    load.potentialDifference(), cars[1].position().distanceTo(cars[2].position()));
            // Leave Loconautics time to retire the last knuckle joint before the server shuts down.
            var frontKnuckle = cars[0].knucklePos(Direction.WEST);
            level.setBlockAndUpdate(frontKnuckle.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            KnuckleConnectionManager.disconnect(level, frontKnuckle);
            cleanup = level.getGameTime() + 30;
        }

        private boolean bothJumpersConnected() {
            return connected(cars[0].jumper(Direction.WEST)) && connected(cars[1].jumper(Direction.EAST))
                    && connected(cars[1].jumper(Direction.WEST)) && connected(cars[2].jumper(Direction.EAST));
        }

        private boolean bothCornersMatched() {
            double voltage = Math.abs(otherCornerLoad.potentialDifference());
            return voltage > 8 && voltage < 9 && connected(cars[1].otherCorner(Direction.WEST))
                    && connected(cars[2].otherCorner(Direction.EAST)) && !connected(cars[1].otherCorner(Direction.EAST));
        }

        private boolean connected(BlockPos pos) {
            return level.getBlockState(pos).getAnalogOutputSignal(level, pos) == 15;
        }

        private void log(String stage, double volts) {
            System.out.printf(Locale.ROOT, "RAIL_JUMPER_%s tick=%d voltage=%.5f min=%.5f max=%.5f lowTicks=%d "
                            + "curve=%s grade=%s tail=%s%n",
                    stage, samples, volts, minimum, maximum, lowTicks, java.util.Arrays.toString(curve),
                    java.util.Arrays.toString(grade), cars[2].position());
        }
    }

    private static Block socketBlock(boolean wall) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(wall ? "powerdock:flush_rail_jumper" : "powerdock:rail_jumper"));
    }

    private static BlockPos startAt(int car) {
        return new BlockPos(35 - car * 10, BASE_Y, START_Z);
    }

    private static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private static BlockState face(BlockState state, Direction direction) {
        if (state.hasProperty(BlockStateProperties.FACING)) return state.setValue(BlockStateProperties.FACING, direction);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
        }
        return state;
    }

    private static BlockPos jumperAt(BlockPos center, int car, Direction direction, boolean otherCorner) {
        int length = direction == Direction.EAST ? 6 : car == 0 ? 0 : -1;
        int height = car == 2 && otherCorner ? 3 : 2;
        return center.offset(length, height, otherCorner ? 2 : -2);
    }

    private record Car(ServerSubLevel body, SableTrain train, BlockPos center, int index) {
        BlockPos jumper(Direction direction) {
            return jumperAt(center, index, direction, false);
        }

        BlockPos otherCorner(Direction direction) {
            return jumperAt(center, index, direction, true);
        }

        BlockWireEndpoint terminal(Direction direction, int pin) {
            return new BlockWireEndpoint(jumper(direction), pin);
        }

        BlockPos knucklePos(Direction direction) {
            return center.above().east(direction == Direction.WEST ? -2 : 7);
        }

        KnuckleBlockEntity knuckle(ServerLevel level, Direction direction) {
            return (KnuckleBlockEntity) level.getBlockEntity(knucklePos(direction));
        }

        boolean coupled(ServerLevel level, Car other) {
            var own = knuckle(level, Direction.WEST);
            var mate = other.knuckle(level, Direction.EAST);
            return own.isConnected() && mate.isConnected()
                    && other.body().getUniqueId().equals(own.getPartnerSubLevelId())
                    && body.getUniqueId().equals(mate.getPartnerSubLevelId());
        }

        Vec3 position() {
            return body.logicalPose().transformPosition(center.getCenter());
        }
    }
}
