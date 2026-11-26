package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.Vec3;

import static org.cha0scollective.powerdock.PonderPresentation.explain;
import static org.cha0scollective.powerdock.PonderPresentation.geometry;
import static org.cha0scollective.powerdock.PonderPresentation.open;

final class OverheadPonders {
    private static final BlockPos FIRST_HANGER = new BlockPos(2, 8, 5);
    private static final BlockPos LAST_HANGER = new BlockPos(8, 8, 5);
    private static final Vec3 ACROSS = new Vec3(0, 0, CatenarySpan.HALF_SPACING);
    private static final Vec3 EAST = new Vec3(1, 0, 0);
    private static final Vec3 UP = new Vec3(0, 1, 0);
    static final int COPPER = 0xffa76548;

    private OverheadPonders() {}

    static void hanger(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "catenary_hanger", "Placing Catenary Hangers", 13, 0.64f);
        scene.setSceneOffsetY(-0.5f);
        var bend = curve(scene, new BlockPos(6, 1, 4), new BlockPos(10, 2, 9));
        scene.world().showSection(util.select().fromTo(1, 1, 4, 12, 2, 9), Direction.DOWN);
        var straight = new BlockPos(3, 1, 4);
        var rail = head(straight, 0);
        var item = new ItemStack(PowerDock.CATENARY_HANGER.get());
        int initialHeight = HangerTrackTarget.INITIAL_HEIGHT;
        scene.overlay().showControls(rail.center(), Pointing.DOWN, 35).rightClick().withItem(item);
        scene.idle(14);
        var hanger = mountHanger(scene, util, rail, initialHeight);
        explain(scene, rail.center(), "Aim at a rail and right-click with a hanger. The assembly snaps four blocks above the track.", 90);

        var onBend = curveHead(bend, 0.55, 0);
        scene.overlay().showControls(onBend.center(), Pointing.DOWN, 35).rightClick().withItem(item);
        scene.idle(14);
        mountHanger(scene, util, onBend, initialHeight);
        explain(scene, onBend.center(), "You can aim partway through a curve or incline. The crossbeam turns across the track at that point.", 96);

        scene.overlay().showOutline(PonderPalette.OUTPUT, straight, util.select().position(straight), 100);
        scene.overlay().showControls(rail.center(), Pointing.DOWN, 38).rightClick().whileSneaking();
        explain(scene, rail.center(), "Look for the highlighted rail. Sneak-right-click it with an empty hand to open Hanger Settings.", 95);

        scene.overlay().showText(105)
                .text("Set Height from 4 to 12 and Width from 3 to 13 blocks. The terminal box, crossbeam and ceramic mounts move together.")
                .pointAt(hanger.getCenter()).placeNearTarget().attachKeyFrame();
        scene.idle(14);
        changeClearance(scene, util, hanger, initialHeight, initialHeight + 1, 7);
        scene.idle(14);
        changeClearance(scene, util, hanger, initialHeight, initialHeight + 2, 7);
        scene.idle(14);
        changeClearance(scene, util, hanger, initialHeight, initialHeight + 2, 9);
        scene.idle(71);
        scene.markAsFinished();
    }

    static void route(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "catenary_route", "Linking Hangers with Copper Wire", 11, 0.61f);
        scene.setSceneOffsetY(-1.1f);
        scene.world().showSection(util.select().fromTo(1, 1, 5, 9, 1, 5), Direction.DOWN);
        showHanger(scene, util, FIRST_HANGER, 0, 7);
        showHanger(scene, util, LAST_HANGER, 0, 7);
        var wire = linkingWire();
        scene.overlay().showControls(FIRST_HANGER.getCenter(), Pointing.DOWN, 40).rightClick().whileSneaking().withItem(wire);
        explain(scene, FIRST_HANGER.getCenter(), "Hold bare Power Grid Copper Wire. Sneak-right-click either hanger to start from that end.", 84);

        var first = head(FIRST_HANGER, 0);
        var last = head(LAST_HANGER, 0);
        scene.overlay().showControls(LAST_HANGER.getCenter(), Pointing.DOWN, 40).rightClick().whileSneaking().withItem(wire);
        scene.idle(12);
        previewWire(scene, first, last, 106);
        explain(scene, LAST_HANGER.getCenter(), "Sneak-right-click the second hanger to preview both wires. The prompt shows that linking is active.", 98);

        var air = last.center().add(0, -2, 2);
        scene.overlay().showControls(air, Pointing.DOWN, 38).rightClick().whileSneaking().withItem(wire);
        scene.overlay().showText(88)
                .text("To cancel, sneak-right-click into empty air away from the track.")
                .pointAt(air).placeNearTarget().attachKeyFrame();
        previewWire(scene, first, last, 32);
        scene.idle(96);

        scene.overlay().showControls(FIRST_HANGER.getCenter(), Pointing.DOWN, 28).rightClick().whileSneaking().withItem(wire);
        scene.idle(16);
        scene.overlay().showControls(LAST_HANGER.getCenter(), Pointing.DOWN, 28).rightClick().whileSneaking().withItem(wire);
        previewWire(scene, first, last, 18);
        scene.idle(18);
        scene.overlay().showControls(LAST_HANGER.getCenter(), Pointing.DOWN, 30).rightClick().whileSneaking().withItem(wire);
        scene.idle(8);
        showWires(scene, first, last);
        scene.effects().indicateSuccess(LAST_HANGER);
        explain(scene, LAST_HANGER.getCenter(), "Sneak-right-click the previewed hanger again to confirm. Each span follows up to 64 blocks of track.", 104);
        scene.markAsFinished();
    }

    static ItemStack linkingWire() {
        return new ItemStack(BuiltInRegistries.ITEM.get(CatenaryWireUse.COPPER_WIRE));
    }

    private static void previewWire(SceneBuilder scene, CatenarySpan.Head first, CatenarySpan.Head last, int ticks) {
        scene.overlay().showLine(PonderPalette.OUTPUT, first.supply(), last.supply(), ticks);
        scene.overlay().showLine(PonderPalette.INPUT, first.returnWire(), last.returnWire(), ticks);
    }

    static BezierConnection curve(SceneBuilder scene, BlockPos start, BlockPos end) {
        var curve = new BezierConnection(Couple.create(start, end),
                Couple.create(Vec3.atBottomCenterOf(start).add(0.5, 0, 0), Vec3.atBottomCenterOf(end).add(-0.5, 0, 0)),
                Couple.create(EAST, EAST.reverse()), Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);
        scene.world().modifyBlockEntity(start, TrackBlockEntity.class, track -> track.getConnections().put(end, curve));
        scene.world().modifyBlockEntity(end, TrackBlockEntity.class, track -> track.getConnections().put(start, curve.secondary()));
        return curve;
    }

    static CatenarySpan.Head curveHead(BezierConnection curve, double t, int height) {
        var tangent = curve.getPosition(Math.min(1, t + 0.002)).subtract(curve.getPosition(Math.max(0, t - 0.002)));
        var across = new Vec3(-tangent.z, 0, tangent.x).normalize().scale(CatenarySpan.HALF_SPACING);
        return new CatenarySpan.Head(curve.getPosition(t).add(0, height, 0), across);
    }

    static BlockPos mountHanger(SceneBuilder scene, SceneBuildingUtil util, CatenarySpan.Head rail, int height) {
        var pos = BlockPos.containing(rail.center().add(0, height + HangerFixture.BEAM_RISE, 0));
        var mount = HangerMount.fromTrack(pos, rail, Direction.EAST);
        scene.world().modifyBlockEntityNBT(util.select().position(pos), CatenaryHangerBlockEntity.class, tag -> {
            var placement = new CompoundTag();
            placement.putDouble("X", mount.rail.x);
            placement.putDouble("Y", mount.rail.y);
            placement.putDouble("Z", mount.rail.z);
            placement.putDouble("Yaw", mount.yaw);
            tag.put("Mount", placement);
            tag.putInt("Height", height);
            tag.putInt("Width", 7);
        });
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        return pos;
    }

    private static void changeClearance(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos, int initialHeight,
                                        int height, int width) {
        scene.world().modifyBlockEntityNBT(util.select().position(pos), CatenaryHangerBlockEntity.class, tag -> {
            tag.putInt("Height", height);
            tag.putInt("Width", width);
            tag.putInt("HeadOffset", height - initialHeight);
        });
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

    static CatenarySpan.Head head(BlockPos hanger, double heightOffset) {
        return new CatenarySpan.Head(Vec3.atBottomCenterOf(hanger).add(0, heightOffset, 0), ACROSS);
    }

    private static void showHanger(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos, double heightOffset, int width) {
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        var wires = head(pos, heightOffset).center();
        var fixture = new HangerFixture(wires, ACROSS, Vec3.atBottomCenterOf(pos), wires.y - 7, width);
        geometry(scene, (level, beams) -> CatenaryRenderer.drawFixture(beams, fixture));
    }

    static void showWires(SceneBuilder scene, CatenarySpan.Head from, CatenarySpan.Head to) {
        geometry(scene, (level, beams) -> {
            beams.beam(from.supply(), to.supply(), 0.03125, COPPER);
            beams.beam(from.returnWire(), to.returnWire(), 0.03125, COPPER);
        });
    }
}
