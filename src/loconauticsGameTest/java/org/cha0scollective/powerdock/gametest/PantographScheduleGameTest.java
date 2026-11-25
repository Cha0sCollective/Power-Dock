package org.cha0scollective.powerdock.gametest;

import com.lycoris.loconautics.content.analogcontroller.AnalogControllerBlockEntity;
import com.lycoris.loconautics.content.bearingaxle.BearingAxleBlockEntity;
import com.lycoris.loconautics.content.schedule.SableScheduleRuntime;
import com.lycoris.loconautics.content.schedule.Schedule;
import com.lycoris.loconautics.content.schedule.ScheduleEntry;
import com.lycoris.loconautics.content.schedule.ScheduleProgressStore;
import com.lycoris.loconautics.content.schedule.ScheduleReadiness;
import com.lycoris.loconautics.content.schedule.condition.ScheduledDelay;
import com.lycoris.loconautics.content.schedule.destination.DestinationInstruction;
import com.lycoris.loconautics.content.transmission.TransmissionBlockEntity;
import com.lycoris.loconautics.trainphysics.SableTrain;
import com.lycoris.loconautics.trainphysics.spawn.SableTrainSpawner;
import com.lycoris.loconautics.trainphysics.state.SableTrainRegistry;
import com.lycoris.loconautics.trainphysics.state.StationChunkTickets;
import com.lycoris.loconautics.trainphysics.state.TrainResidency;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.cha0scollective.powerdock.PantographBlockEntity;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity;

import java.util.List;
import java.util.Locale;

@GameTestHolder("powerdock_loconautics_tests")
@PrefixGameTestTemplate(false)
public final class PantographScheduleGameTest {
    private static final BlockPos START = new BlockPos(9, 67, 648);
    private static final BlockPos FEEDER = START.west(5).above(6);
    private static final int SPAN_LENGTH = 40;
    private static final int SPANS = 17;
    private static final BlockPos STATION_RAIL = FEEDER.below(7).east(SPANS * SPAN_LENGTH - 21);
    private static final BlockPos STATION = STATION_RAIL.south(3);
    private static final String DESTINATION = "Power Dock East";
    private static final BlockPos PICKUP = new BlockPos(2, 4, 0);
    private static final BlockPos MOTOR = new BlockPos(-3, 0, 0);
    private static final BlockPos CAB = new BlockPos(3, 2, 0);
    private static final ChunkPos FEEDER_CHUNK = new ChunkPos(FEEDER);
    private static final ChunkPos DESTINATION_CHUNK = new ChunkPos(STATION);
    private static final int CHUNK_BUFFER = 24;

    @GameTest(template = "empty", timeoutTicks = 5200)
    public static void scheduledElectricTrainLoadsUnattendedStation(GameTestHelper test) {
        var trip = new Trip(test);
        trip.build();
        test.runAtTickTime(25, trip::assemble);
        test.runAtTickTime(70, trip::connectPower);
        test.runAtTickTime(115, trip::releaseTrack);
        test.onEachTick(trip::check);
    }

    private static final class Trip {
        private final GameTestHelper test;
        private final ServerLevel level;
        private SableTrain train;
        private BlockPos pickupPos, motorPos;
        private SableScheduleRuntime schedule;
        private StationBlockEntity originalStation;
        private CatenaryHangerBlockEntity middleHanger;
        private boolean crossedUnloadedMiddle;
        private boolean destinationLoaded;
        private long released;
        private long departure;
        private double ghostEnteredAt = Double.NaN;
        private int restoredPowerTicks;

        Trip(GameTestHelper test) {
            this.test = test;
            level = test.getLevel();
        }

        void build() {
            int lastX = hangerPos(SPANS).getX() + 7;
            for (int x = 0; x <= lastX >> 4; x++) level.setChunkForced(x, FEEDER_CHUNK.z, true);
            for (int x = 2; x <= lastX; x++) {
                var rail = new BlockPos(x, START.getY() - 1, START.getZ());
                put(rail.below(), Blocks.STONE.defaultBlockState());
                put(rail, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
            }
            put(STATION, AllBlocks.TRACK_STATION.getDefaultState());
            originalStation = (StationBlockEntity) level.getBlockEntity(STATION);
            var target = new CompoundTag();
            target.put("TargetTrack", NbtUtils.writeBlockPos(STATION_RAIL.subtract(STATION)));
            target.putBoolean("TargetDirection", false);
            target.putBoolean("Ortho", true);
            originalStation.edgePoint.read(target, level.registryAccess(), false);

            for (int i = 0; i <= SPANS; i++) put(hangerPos(i), block("powerdock:catenary_hanger"));
            for (int x = -3; x <= 6; x++) for (int z = -1; z <= 1; z++) {
                if (x < 0 && z == 0) continue;
                put(START.offset(x, 1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            }
            for (int x : new int[]{0, 5}) put(START.east(x), AllBlocks.SMALL_BOGEY.getDefaultState()
                    .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
            put(START.west(), face("loconautics:bearing_axle", Direction.WEST));
            put(START.west(2), block("loconautics:transmission").setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            put(START.offset(MOTOR), block("powergrid:electric_motor").setValue(BlockStateProperties.FACING, Direction.EAST));
            put(START.offset(CAB), face("loconautics:red_analog_controller", Direction.WEST));
            var conductor = START.offset(CAB).west();
            put(conductor, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL,
                    BlazeBurnerBlock.HeatLevel.SMOULDERING));
            ((BlazeBurnerBlockEntity) level.getBlockEntity(conductor)).hat = true;
            var roof = START.offset(PICKUP);
            put(roof.below(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            var pantograph = face("powerdock:pantograph", Direction.EAST);
            var raised = (BooleanProperty) pantograph.getBlock().getStateDefinition().getProperty("raised");
            put(roof, pantograph.setValue(raised, true));
            level.addFreshEntity(new SuperGlueEntity(level, new AABB(Vec3.atLowerCornerOf(START.offset(-3, 0, -2)),
                    Vec3.atLowerCornerOf(START.offset(7, 5, 3)))));
        }

        void assemble() {
            TrackPropagator.onRailAdded(level, START.below(), level.getBlockState(START.below()));
            var station = (StationBlockEntity) level.getBlockEntity(STATION);
            test.assertTrue(station.getStation() != null && station.updateName(DESTINATION), "The destination station did not bind to its track");
            train = SableTrainSpawner.assembleAt(level, START, new Vec3(1, 0, 0), null, "Pantograph-only locomotive");
            test.assertTrue(train != null && train.car().bogeys().size() == 2, "The test locomotive did not assemble");
            var center = body().getPlot().getCenterBlock();
            pickupPos = center.offset(PICKUP);
            motorPos = center.offset(MOTOR);
            var cab = (AnalogControllerBlockEntity) level.getBlockEntity(center.offset(CAB));
            var transmission = (TransmissionBlockEntity) level.getBlockEntity(center.west(2));
            var axle = (BearingAxleBlockEntity) level.getBlockEntity(center.west());
            var forward = new ItemStack(Items.COPPER_INGOT);
            var reverse = new ItemStack(Items.IRON_INGOT);
            var left = new ItemStack(Items.LAPIS_LAZULI);
            var right = new ItemStack(Items.REDSTONE);
            cab.setFrequency(forward, ItemStack.EMPTY);
            transmission.setSpeedFrequency(forward, ItemStack.EMPTY);
            cab.setBackwardFrequency(reverse, ItemStack.EMPTY);
            transmission.setDirectionFrequency(reverse, ItemStack.EMPTY);
            cab.setLeftFrequency(left, ItemStack.EMPTY);
            axle.setLeftFrequency(left, ItemStack.EMPTY);
            cab.setRightFrequency(right, ItemStack.EMPTY);
            axle.setRightFrequency(right, ItemStack.EMPTY);
        }

        void connectPower() {
            test.assertTrue(motor() != null && motor().getGeneratedSpeed() == 0, "The motor turned without overhead power");
            var copper = BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:insulated_copper_wire"));
            for (int pin = 0; pin < 2; pin++) {
                var wire = WireItem.connect(level, new ItemStack(copper, 64), null,
                        new BlockWireEndpoint(pickupPos, pin), new BlockWireEndpoint(motorPos, pin));
                test.assertTrue(wire.getObject() != null, "The onboard copper wire could not be routed on pin " + pin);
            }
            middleHanger = hanger(SPANS / 2);
            for (int i = 1; i <= SPANS; i++) {
                test.assertTrue(hanger(i - 1).linkTo(hanger(i)), "Could not link the overhead span ending at " + hangerPos(i));
            }
            var grid = GlobalElectricNetworks.getWorldNetworks(level);
            var positive = new BlockWireEndpoint(FEEDER, 0);
            var negative = new BlockWireEndpoint(FEEDER, 1);
            var circuit = grid.prepareForConnection(positive, negative);
            var source = new CurrentSourceWire(positive.getNode(level), negative.getNode(level), 1);
            source.setCurrent(210);
            circuit.addWire(source);
        }

        void releaseTrack() {
            test.assertTrue(powered(), "The pantograph did not start the only motor, V=" + voltage());
            int lastChunk = (hangerPos(SPANS).getX() + 7) >> 4;
            for (int x = 1; x <= lastChunk; x++) level.setChunkForced(x, FEEDER_CHUNK.z, false);
            released = level.getGameTime();
        }

        void depart() {
            test.assertTrue(powered(), "The train lost overhead power while waiting to depart");
            var cabPos = body().getPlot().getCenterBlock().offset(CAB);
            var readiness = ScheduleReadiness.check(body(), (AnalogControllerBlockEntity) level.getBlockEntity(cabPos), train.id());
            test.assertTrue(readiness.ok(), "Loconautics would not accept the schedule: " + readiness.reasonKey());
            var instruction = new DestinationInstruction();
            instruction.getData().putString("Text", DESTINATION);
            var plan = new Schedule(List.of(new ScheduleEntry(instruction, List.of(List.of(new ScheduledDelay())))), false, 0);
            ScheduleProgressStore.get(level.getServer()).putSchedule(train.id(), plan.write(level.registryAccess()));
            schedule = SableScheduleRuntime.assign(train.id(), plan);
            departure = level.getGameTime();
            System.out.println("PANTOGRAPH_TRIP UNATTENDED players=0 forcedChunksNearRoute=" + forcedChunksNearRoute()
                    + " destinationLoaded=false stationTickets=" + StationChunkTickets.describe(train));
            report("DEPART", TrainResidency.railCursor(train.car()).x());
        }

        void check() {
            if (released == 0) return;
            var destination = level.getChunkSource().getChunkNow(DESTINATION_CHUNK.x, DESTINATION_CHUNK.z);
            boolean onlyFeederForced = forcedChunksNearRoute() == 1
                    && level.getForcedChunks().contains(FEEDER_CHUNK.toLong());
            test.assertTrue(level.players().isEmpty(), "A player entered the unattended test world");
            if (schedule == null) {
                // Other GameTests use distant fixtures. Wait for their temporary force loads to clear as well.
                if (onlyFeederForced && destination == null && originalStation.isChunkUnloaded()) depart();
                else test.assertTrue(level.getGameTime() - released < 1500, "Could not start unattended: forced chunks near route="
                        + forcedChunksNearRoute() + ", station chunk absent=" + (destination == null)
                        + ", station block entity unloaded=" + originalStation.isChunkUnloaded());
                return;
            }
            test.assertTrue(onlyFeederForced, "Something besides the feeder is manually force-loaded");
            var cursor = TrainResidency.railCursor(train.car());
            double x = cursor.x();
            long elapsed = level.getGameTime() - departure;
            test.assertTrue(!train.isDerailed(), "The scheduled train derailed at x=" + x);
            if (train.isGhost()) {
                if (Double.isNaN(ghostEnteredAt)) {
                    ghostEnteredAt = x;
                    report("GHOST_ENTER", x);
                }
                if (!crossedUnloadedMiddle && x >= middleHanger.getBlockPos().getX()) {
                    var chunk = new ChunkPos(BlockPos.containing(cursor.x(), cursor.y(), cursor.z()));
                    boolean chunkAbsent = level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null;
                    boolean hangerUnloaded = middleHanger.isChunkUnloaded();
                    var stored = body();
                    boolean trainUnloaded = stored == null || stored.isRemoved();
                    test.assertTrue(chunkAbsent && hangerUnloaded && trainUnloaded,
                            "Midpoint state: chunk absent=" + chunkAbsent + ", hanger unloaded=" + hangerUnloaded
                                    + ", physical train unloaded=" + trainUnloaded);
                    crossedUnloadedMiddle = true;
                    report("UNLOADED_MIDPOINT", x);
                }
            }
            if (!destinationLoaded && destination != null) {
                test.assertTrue(crossedUnloadedMiddle, "The station loaded before the train crossed the unloaded track");
                var tickets = StationChunkTickets.describe(train);
                test.assertTrue(tickets.startsWith("holding "), "The station loaded without a Loconautics ticket: " + tickets);
                destinationLoaded = true;
                System.out.println("PANTOGRAPH_TRIP STATION_LOADED " + tickets);
                report("STATION_LOADED", x);
            }
            if (crossedUnloadedMiddle && !train.isGhost()) {
                var center = body().getPlot().getCenterBlock();
                pickupPos = center.offset(PICKUP);
                motorPos = center.offset(MOTOR);
            }
            if (crossedUnloadedMiddle && !train.isGhost() && powered()) {
                if (restoredPowerTicks++ == 0) report("POWER_RESTORED", x);
            } else {
                restoredPowerTicks = 0;
            }
            var station = train.currentStation();
            if (destinationLoaded && restoredPowerTicks >= 20 && station != null && DESTINATION.equals(station.name)) {
                report("ARRIVED", x);
                ScheduleProgressStore.get(level.getServer()).remove(train.id());
                SableScheduleRuntime.clear(train.id());
                StationChunkTickets.release(train.id(), "unattended station test complete");
                level.setChunkForced(FEEDER_CHUNK.x, FEEDER_CHUNK.z, false);
                test.succeed();
            } else if (elapsed % 200 == 0) {
                report("PROGRESS", x);
            }
            test.assertTrue(elapsed < 3000, "The scheduled trip stalled at x=" + x + " ghost=" + train.isGhost()
                    + " voltage=" + voltage() + " held=" + schedule.heldReason() + " destination loaded=" + destinationLoaded
                    + " station tickets=" + StationChunkTickets.describe(train));
        }

        private int forcedChunksNearRoute() {
            // Ignore the GameTest structures themselves, which the harness force-loads far from this route.
            int count = 0;
            for (long packed : level.getForcedChunks()) {
                var chunk = new ChunkPos(packed);
                if (chunk.x >= FEEDER_CHUNK.x - CHUNK_BUFFER && chunk.x <= DESTINATION_CHUNK.x + CHUNK_BUFFER
                        && Math.abs(chunk.z - FEEDER_CHUNK.z) <= CHUNK_BUFFER) count++;
            }
            return count;
        }

        private boolean powered() {
            var motor = motor();
            var pickup = level.getBlockEntity(pickupPos);
            return pickup instanceof PantographBlockEntity pantograph && pantograph.connected()
                    && voltage() > 50 && motor != null && Math.abs(motor.getGeneratedSpeed()) > 5;
        }

        private double voltage() {
            if (train.isGhost()) return 0;
            var p = new BlockWireEndpoint(motorPos, 0).getNode(level);
            var n = new BlockWireEndpoint(motorPos, 1).getNode(level);
            return p == null || n == null ? 0 : Math.abs(p.getVoltage() - n.getVoltage());
        }

        private ElectricMotorBlockEntity motor() {
            return level.getBlockEntity(motorPos) instanceof ElectricMotorBlockEntity motor ? motor : null;
        }

        private ServerSubLevel body() {
            return (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(train.car().subLevelId());
        }

        private CatenaryHangerBlockEntity hanger(int index) {
            return (CatenaryHangerBlockEntity) level.getBlockEntity(hangerPos(index));
        }

        private void put(BlockPos pos, BlockState state) {
            level.setBlockAndUpdate(pos, state);
        }

        private void report(String phase, double x) {
            var motor = train.isGhost() ? null : motor();
            var body = train.isGhost() ? null : body();
            var center = body == null ? null : body.getPlot().getCenterBlock();
            var cab = center == null ? null : (AnalogControllerBlockEntity) level.getBlockEntity(center.offset(CAB));
            var gear = center == null ? null : (TransmissionBlockEntity) level.getBlockEntity(center.west(2));
            System.out.printf(Locale.ROOT, "PANTOGRAPH_TRIP %s x=%.2f ghost=%s speed=%.3f voltage=%.2f rpm=%.1f held=%s "
                            + "schedule=%s paused=%s completed=%s distance=%.1f targetSpeed=%.3f throttle=%d gear=%d/%s "
                            + "runtimeTicked=%s registered=%s physics=%s trainStation=%s%n",
                    phase, x, train.isGhost(), train.speed(), voltage(), motor == null ? 0 : motor.getGeneratedSpeed(),
                    schedule.heldReason(), schedule.state, schedule.paused, schedule.completed, schedule.remainingToTarget(), train.targetSpeed(),
                    cab == null ? 0 : cab.getCurrentPower(), gear == null ? 0 : gear.getPower(),
                    gear == null ? "-" : gear.getTargetRpm(), schedule.train() == train, SableTrainRegistry.get(train.id()) == train,
                    train.isPhysics(), train.currentStation() == null ? "-" : train.currentStation().name);
        }
    }

    private static BlockPos hangerPos(int index) {
        return FEEDER.east(index * SPAN_LENGTH);
    }

    private static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private static BlockState face(String id, Direction direction) {
        return block(id).setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
    }
}
