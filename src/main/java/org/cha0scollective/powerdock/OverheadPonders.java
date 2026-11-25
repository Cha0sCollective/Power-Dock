package org.cha0scollective.powerdock;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.Vec3;

import static org.cha0scollective.powerdock.PonderPresentation.explain;
import static org.cha0scollective.powerdock.PonderPresentation.geometry;
import static org.cha0scollective.powerdock.PonderPresentation.open;

final class OverheadPonders {
    private static final BlockPos FIRST_HANGER = new BlockPos(2, 8, 5);
    private static final BlockPos LAST_HANGER = new BlockPos(8, 8, 5);
    private static final Vec3 ACROSS = new Vec3(0, 0, CatenarySpan.HALF_SPACING);
    private static final int COPPER = 0xffa76548;

    private OverheadPonders() {}

    static void hanger(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "catenary_hanger", "Placing Catenary Hangers", 11, 0.61f);
        scene.setSceneOffsetY(-1.1f);
        scene.world().showSection(util.select().fromTo(1, 1, 5, 9, 1, 5), Direction.DOWN);
        explain(scene, new Vec3(2.5, 1.5, 5.5),
                "Place a hanger directly above the rail. It can also sit partway through a curve or climb.", 90);
        showHanger(scene, util, FIRST_HANGER, 0, 7);
        showHanger(scene, util, LAST_HANGER, 0, 7);
        showWires(scene, head(FIRST_HANGER, 0), head(LAST_HANGER, 0));
        explain(scene, head(FIRST_HANGER, 0).center(),
                "The hanger lines up across the track as you place it. Add hangers along bends and climbs to support both wires.", 100);
        var target = FIRST_HANGER.getCenter();
        scene.overlay().showControls(target, Pointing.DOWN, 38).rightClick();
        explain(scene, target, "Right-click with an empty hand to change the width. The default opening is 7 blocks wide.", 90);
        scene.overlay().showControls(target, Pointing.DOWN, 38).rightClick().whileSneaking();
        explain(scene, target, "Sneak-right-click with an empty hand to set the wire height. Leave room for the train and the roof pickup.", 95);
        scene.markAsFinished();
    }

    static void route(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "catenary_route", "Linking Hangers with Copper Wire", 11, 0.61f);
        scene.setSceneOffsetY(-1.1f);
        scene.world().showSection(util.select().fromTo(1, 1, 5, 9, 1, 5), Direction.DOWN);
        showHanger(scene, util, FIRST_HANGER, 0, 7);
        showHanger(scene, util, LAST_HANGER, 0, 7);
        var wire = new ItemStack(BuiltInRegistries.ITEM.get(CatenaryWireUse.COPPER_WIRE));
        scene.overlay().showControls(FIRST_HANGER.getCenter(), Pointing.DOWN, 40).rightClick().whileSneaking().withItem(wire);
        explain(scene, FIRST_HANGER.getCenter(), "Hold Power Grid Copper Wire and sneak-right-click the first hanger.", 80);

        scene.overlay().showControls(LAST_HANGER.getCenter(), Pointing.DOWN, 40).rightClick().whileSneaking().withItem(wire);
        scene.idle(12);
        var first = head(FIRST_HANGER, 0);
        var last = head(LAST_HANGER, 0);
        scene.overlay().showLine(PonderPalette.OUTPUT, first.supply(), last.supply(), 98);
        scene.overlay().showLine(PonderPalette.INPUT, first.returnWire(), last.returnWire(), 98);
        explain(scene, LAST_HANGER.getCenter(), "Sneak-right-click the next hanger to preview both paths. Sneak-right-click it again to confirm.", 90);
        showWires(scene, first, last);
        scene.effects().indicateSuccess(LAST_HANGER);
        explain(scene, LAST_HANGER.getCenter(), "Start the next span from this hanger. On a branch, use closer hangers to make the route clear.", 95);
        scene.overlay().showControls(FIRST_HANGER.getCenter(), Pointing.DOWN, 40).rightClick().withItem(wire);
        explain(scene, FIRST_HANGER.getCenter(), "Use normal right-clicks on the first hanger's copper posts to wire the section into Power Grid.", 94);
        explain(scene, LAST_HANGER.getCenter(), "To remove spans at a hanger, sneak-right-click it three times with Copper Wire and follow the prompt.", 90);
        scene.markAsFinished();
    }

    static void pantograph(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "pantograph", "Collecting Overhead Power", 15, 0.51f);
        scene.setSceneOffsetY(-0.9f);
        scene.world().showSection(util.select().fromTo(1, 1, 6, 13, 1, 6), Direction.DOWN);
        var a = new BlockPos(1, 8, 6);
        var b = new BlockPos(6, 8, 6);
        var c = new BlockPos(9, 8, 6);
        var d = new BlockPos(13, 8, 6);
        for (var pos : new BlockPos[]{a, b, c, d}) showHanger(scene, util, pos, 0.3125, 7);
        showWires(scene, head(a, 0.3125), head(b, 0.3125));
        showWires(scene, head(c, 0.3125), head(d, 0.3125));

        var pickup = new BlockPos(2, 7, 6);
        var cabLever = new BlockPos(3, 3, 5);
        var car = scene.world().showIndependentSection(util.select().fromTo(1, 2, 5, 4, 7, 7), Direction.DOWN);
        explain(scene, pickup.getCenter(), "Mount the pantograph along the carriage and connect its two posts to the onboard circuit.", 90);
        scene.overlay().showControls(pickup.getCenter(), Pointing.DOWN, 35).rightClick();
        scene.idle(10);
        setPickup(scene, pickup, true, true);
        explain(scene, pickup.getCenter(), "Right-click with an empty hand to raise it. The contact bar follows both overhead wires.", 86);

        scene.world().moveSection(car, new Vec3(3, 0, 0), 28);
        scene.idle(30);
        setPickup(scene, pickup, true, false);
        scene.world().moveSection(car, new Vec3(2.5, 0, 0), 27);
        scene.idle(28);
        explain(scene, new Vec3(7.5, 8.3, 6.5), "At an insulated gap, the head stays raised while the onboard circuit disconnects.", 85);
        scene.world().moveSection(car, new Vec3(2.5, 0, 0), 27);
        scene.idle(28);
        setPickup(scene, pickup, true, true);
        explain(scene, new Vec3(10.5, 8.3, 6.5), "The pickup reconnects automatically under the next powered section.", 76);

        var cabOffset = new Vec3(8, 0, 0);
        scene.overlay().showControls(pickup.getCenter().add(cabOffset), Pointing.DOWN, 40).rightClick().whileSneaking();
        explain(scene, pickup.getCenter().add(cabOffset),
                "Sneak-right-click with an empty hand to open the receiver. Match both item slots to the cab's transmitting Redstone Link.", 103);
        setPickup(scene, pickup, false, false);
        scene.idle(15);
        scene.overlay().showControls(cabLever.getCenter().add(cabOffset), Pointing.DOWN, 32).rightClick();
        scene.idle(8);
        scene.world().modifyBlock(cabLever, state -> state.setValue(LeverBlock.POWERED, true), false);
        setPickup(scene, pickup, true, true);
        explain(scene, cabLever.getCenter().add(cabOffset), "Turn the cab signal on to raise the pickup from inside the train.", 74);
        scene.overlay().showControls(cabLever.getCenter().add(cabOffset), Pointing.DOWN, 32).rightClick();
        scene.idle(8);
        scene.world().modifyBlock(cabLever, state -> state.setValue(LeverBlock.POWERED, false), false);
        setPickup(scene, pickup, false, false);
        explain(scene, cabLever.getCenter().add(cabOffset), "Turn the signal off to lower it and disconnect power.", 75);
        scene.markAsFinished();
    }

    private static void setPickup(SceneBuilder scene, BlockPos pos, boolean raised, boolean connected) {
        scene.world().modifyBlock(pos, state -> state.setValue(CatenaryBlock.RAISED, raised)
                .setValue(CatenaryBlock.CONNECTED, connected), false);
    }

    private static CatenarySpan.Head head(BlockPos hanger, double heightOffset) {
        return new CatenarySpan.Head(Vec3.atBottomCenterOf(hanger).add(0, heightOffset, 0), ACROSS);
    }

    private static void showHanger(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos, double heightOffset, int width) {
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        geometry(scene, (level, beams) -> CatenaryRenderer.drawFixture(beams, head(pos, heightOffset).center(),
                ACROSS, pos.getY(), width, 7));
    }

    private static void showWires(SceneBuilder scene, CatenarySpan.Head from, CatenarySpan.Head to) {
        geometry(scene, (level, beams) -> {
            beams.beam(from.supply(), to.supply(), 0.03125, COPPER);
            beams.beam(from.returnWire(), to.returnWire(), 0.03125, COPPER);
        });
    }
}
