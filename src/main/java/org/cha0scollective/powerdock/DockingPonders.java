package org.cha0scollective.powerdock;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.IElectric;

import static org.cha0scollective.powerdock.PonderPresentation.explain;
import static org.cha0scollective.powerdock.PonderPresentation.geometry;
import static org.cha0scollective.powerdock.PonderPresentation.open;

final class DockingPonders {
    private static final BlockPos STATION = new BlockPos(3, 2, 4);
    private static final BlockPos SHIP = new BlockPos(6, 2, 4);
    private static final BlockPos STATION_LEVER = new BlockPos(2, 2, 5);
    private static final BlockPos SHIP_LEVER = new BlockPos(7, 2, 5);
    private static final BlockPos JUMPER_A = new BlockPos(4, 3, 4);
    private static final BlockPos JUMPER_B = new BlockPos(7, 3, 4);
    private static final BlockPos KNUCKLE_A = new BlockPos(4, 2, 5);
    private static final BlockPos KNUCKLE_B = new BlockPos(7, 2, 5);
    private static final ResourceLocation KNUCKLE = ResourceLocation.fromNamespaceAndPath("loconautics", "knuckle");
    private static final ResourceLocation NETWORKING_CABLE = ResourceLocation.fromNamespaceAndPath("computercraft", "cable");

    private DockingPonders() {}

    static void dock(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "power_dock", "Docking and Transferring Power", 10, 0.86f);
        scene.world().showSection(util.select().fromTo(1, 1, 3, 3, 2, 5), Direction.DOWN);
        var craft = scene.world().showIndependentSection(util.select().fromTo(6, 1, 3, 8, 2, 5), Direction.DOWN);
        explain(scene, STATION.getCenter(), "Mount facing Power Docks on two Sable craft, or put one on a fixed station.", 87);

        var stationState = PowerDock.DOCK.get().defaultBlockState().setValue(PowerDockBlock.FACING, Direction.EAST);
        markTerminals(scene, STATION, stationState, PowerDock.DOCK.get());
        explain(scene, util.vector().topOf(STATION),
                "Wire Positive and Negative into the local circuit on each side. The docks join matching posts in parallel.", 96);
        setLever(scene, STATION_LEVER, true);
        setLever(scene, SHIP_LEVER, true);
        scene.effects().indicateRedstone(STATION_LEVER);
        scene.effects().indicateRedstone(SHIP_LEVER);
        explain(scene, new Vec3(5, 2.7, 4.5), "Arm both docks with redstone and bring the craft together slowly.", 82);

        scene.world().moveSection(craft, new Vec3(-1.125, 0, 0), 32);
        scene.idle(32);
        setDock(scene, STATION, true, false, false);
        setDock(scene, SHIP, true, false, false);
        scene.world().moveSection(craft, new Vec3(-0.1875, 0, 0), 10);
        scene.idle(12);
        setDock(scene, STATION, true, true, false);
        setDock(scene, SHIP, true, true, false);
        explain(scene, new Vec3(4.345, 2.5, 4.5),
                "As the heads meet, the amber lamps light. Power can flow either way and a comparator reads 15.", 93);

        scene.overlay().showControls(STATION_LEVER.getCenter(), Pointing.DOWN, 35).rightClick();
        scene.idle(10);
        setLever(scene, STATION_LEVER, false);
        setDock(scene, STATION, false, false, true);
        setDock(scene, SHIP, false, false, true);
        scene.idle(8);
        setDock(scene, STATION, false, false, false);
        setDock(scene, SHIP, false, false, false);
        scene.world().moveSection(craft, new Vec3(1.3125, 0, 0), 28);
        explain(scene, STATION_LEVER.getCenter(), "Turn off either dock to open the circuit, retract the arms, and release that pair.", 95);
        scene.markAsFinished();
    }

    static void deckJumper(SceneBuilder scene, SceneBuildingUtil util) {
        jumper(scene, util, false);
    }

    static void flushJumper(SceneBuilder scene, SceneBuildingUtil util) {
        jumper(scene, util, true);
    }

    private static void jumper(SceneBuilder scene, SceneBuildingUtil util, boolean flush) {
        open(scene, flush ? "flush_rail_jumper" : "deck_rail_jumper",
                flush ? "Wiring a Flush Rail Jumper" : "Wiring a Deck Rail Jumper", 12, 0.74f);
        scene.world().showSection(util.select().fromTo(1, 1, 5, 10, 1, 5), Direction.DOWN);
        placeKnuckle(scene, KNUCKLE_A, Direction.EAST);
        placeKnuckle(scene, KNUCKLE_B, Direction.WEST);
        scene.world().showSection(util.select().fromTo(1, 2, 3, 4, 4, 6), Direction.DOWN);
        var nextCar = scene.world().showIndependentSection(util.select().fromTo(7, 2, 3, 10, 4, 6), Direction.DOWN);

        explain(scene, JUMPER_A.getCenter(), flush
                ? "Fit the Flush Rail Jumper into a Loconautics end wall. Install it from inside, facing the next car."
                : "Mount the Deck Rail Jumper on an open Loconautics deck. Point the cord outlet at the next car.", 101);
        explain(scene, KNUCKLE_A.getCenter(),
                "Match the outlet to a knuckle that faces the same way. Corner mounts work when the next car has a matching jumper.", 99);

        var block = flush ? PowerDock.FLUSH_RAIL_JUMPER.get() : PowerDock.RAIL_JUMPER.get();
        var state = block.defaultBlockState().setValue(RailJumperBlock.FACING, Direction.EAST);
        if (flush) {
            scene.rotateCameraY(80);
            scene.idle(24);
        }
        markTerminals(scene, JUMPER_A, state, block);
        explain(scene, JUMPER_A.getCenter(), flush
                ? "Wire both copper posts from inside the car. The power posts and the square data socket share the inside face."
                : "Wire the two copper posts on top to the car's Power Grid circuit. Keep Positive and Negative matched.", 95);
        var socket = JUMPER_A.getCenter().add(flush ? -.48 : 0, flush ? .19 : .46, 0);
        BuiltInRegistries.ITEM.getOptional(NETWORKING_CABLE).ifPresent(cable ->
                scene.overlay().showControls(socket, Pointing.DOWN, 46).rightClick().withItem(new ItemStack(cable)));
        explain(scene, socket, flush
                ? "With CC: Tweaked, put a Networking Cable directly against the inside socket. The cord comes out on the other side of the wall."
                : "With CC: Tweaked, place a Networking Cable directly above the socket to join the cars' wired networks.", 107);
        if (flush) {
            scene.rotateCameraY(-48);
            scene.world().setBlock(JUMPER_A.above(), Blocks.AIR.defaultBlockState(), false);
            scene.world().setBlock(JUMPER_B.above(), Blocks.AIR.defaultBlockState(), false);
            scene.world().setBlock(JUMPER_A.north(), Blocks.AIR.defaultBlockState(), false);
            scene.world().setBlock(JUMPER_B.north(), Blocks.AIR.defaultBlockState(), false);
            scene.idle(24);
        }

        scene.world().moveSection(nextCar, new Vec3(-1.25, 0, 0), 26);
        scene.idle(28);
        setJumper(scene, JUMPER_A, true);
        setJumper(scene, JUMPER_B, true);
        scene.debug().enqueueCallback(active -> {
            var movingCar = active.resolve(nextCar);
            PonderPresentation.addGeometry(active, (level, beams) -> {
                if (!level.getBlockState(JUMPER_A).getValue(RailJumperBlock.CONNECTED)) return;
                var from = Vec3.atBottomCenterOf(JUMPER_A).add(0.5, 0.375, 0);
                var to = Vec3.atBottomCenterOf(JUMPER_B).add(-0.5, 0.375, 0)
                        .add(movingCar.getAnimatedOffset());
                drawCord(beams, from, to);
            });
        });
        explain(scene, new Vec3(5.4, flush ? 3.0 : 3.4, 4.5),
                "When the knuckles couple, the cord connects power and optional data. The amber lamp shows the connection.", 96);

        scene.world().moveSection(nextCar, new Vec3(0.28, 0.28, 0.45), 24);
        scene.idle(26);
        explain(scene, new Vec3(5.5, flush ? 3.0 : 3.4, 4.6), "The cord flexes as the coupled cars turn and climb.", 68);
        setJumper(scene, JUMPER_A, false);
        setJumper(scene, JUMPER_B, false);
        scene.world().moveSection(nextCar, new Vec3(0.97, -0.28, -0.45), 24);
        explain(scene, KNUCKLE_A.getCenter(), "Uncouple the knuckles to retract the cord and disconnect both circuits.", 90);
        scene.markAsFinished();
    }

    private static void drawCord(CatenaryRenderer.Beams beams, Vec3 from, Vec3 to) {
        var previous = from;
        double sag = Math.clamp(from.distanceTo(to) * 0.14, 0.08, 0.42);
        for (int i = 1; i <= 12; i++) {
            double along = i / 12.0;
            var point = from.lerp(to, along).add(0, -sag * 4 * along * (1 - along), 0);
            beams.beam(previous, point, 0.067, 0xff3b3530);
            previous = point;
        }
    }

    private static void placeKnuckle(SceneBuilder scene, BlockPos pos, Direction facing) {
        BuiltInRegistries.BLOCK.getOptional(KNUCKLE).ifPresent(block -> {
            var state = block.defaultBlockState();
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            scene.world().setBlock(pos, state, false);
        });
    }

    private static void markTerminals(SceneBuilder scene, BlockPos pos, BlockState state, IElectric target) {
        var origin = Vec3.atLowerCornerOf(pos);
        var positive = origin.add(target.terminal(state, 0).getOrigin());
        var negative = origin.add(target.terminal(state, 1).getOrigin());
        scene.overlay().showLine(PonderPalette.RED, positive, positive.add(0, 0.64, 0), 94);
        scene.overlay().showLine(PonderPalette.BLUE, negative, negative.add(0, 0.64, 0), 94);
    }

    private static void setDock(SceneBuilder scene, BlockPos pos, boolean deployed, boolean connected, boolean retracting) {
        scene.world().modifyBlock(pos, state -> state.setValue(PowerDockBlock.DEPLOYED, deployed)
                .setValue(PowerDockBlock.CONNECTED, connected).setValue(PowerDockBlock.RETRACTING, retracting), false);
    }

    private static void setJumper(SceneBuilder scene, BlockPos pos, boolean connected) {
        scene.world().modifyBlock(pos, state -> state.setValue(RailJumperBlock.CONNECTED, connected), false);
    }

    private static void setLever(SceneBuilder scene, BlockPos pos, boolean powered) {
        scene.world().modifyBlock(pos, state -> state.setValue(LeverBlock.POWERED, powered), false);
    }
}
