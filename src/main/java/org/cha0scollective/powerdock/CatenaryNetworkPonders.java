package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.track.BezierConnection;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.IElectric;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.cha0scollective.powerdock.OverheadPonders.COPPER;
import static org.cha0scollective.powerdock.OverheadPonders.curve;
import static org.cha0scollective.powerdock.OverheadPonders.curveHead;
import static org.cha0scollective.powerdock.OverheadPonders.head;
import static org.cha0scollective.powerdock.OverheadPonders.linkingWire;
import static org.cha0scollective.powerdock.OverheadPonders.mountHanger;
import static org.cha0scollective.powerdock.OverheadPonders.showWires;
import static org.cha0scollective.powerdock.PonderPresentation.explain;
import static org.cha0scollective.powerdock.PonderPresentation.geometry;
import static org.cha0scollective.powerdock.PonderPresentation.open;

final class CatenaryNetworkPonders {
    private static final BlockPos WEST_RAIL = new BlockPos(2, 1, 6);
    private static final BlockPos EAST_RAIL = new BlockPos(16, 1, 6);
    private static final BlockPos SIDING_RAIL = new BlockPos(9, 1, 11);
    private static final BlockPos SIDING_ENTRY = new BlockPos(8, 1, 11);
    private static final BlockPos SIDING_EXIT = new BlockPos(11, 1, 11);
    private static final BlockPos WEST_FEED = new BlockPos(2, 1, 3);
    private static final BlockPos EAST_FEED = new BlockPos(16, 1, 3);
    private static final int WIRE_HEIGHT = 7;
    private static final int LEAD_COLOR = 0xff42423f;

    private CatenaryNetworkPonders() {}

    static void branches(SceneBuilder scene, SceneBuildingUtil util) {
        var line = showLayout(scene, util, "catenary_branches", "Branches and Loops");
        var wire = linkingWire();
        showWires(scene, line.westWire(), line.eastWire());
        explain(scene, line.westWire().center(), "Start with a main line. A single hanger can serve more than one branch.", 86);

        selectWire(scene, line.westHanger().getCenter(), wire);
        scene.idle(16);
        scene.overlay().showOutline(PonderPalette.OUTPUT, SIDING_ENTRY, util.select().position(SIDING_ENTRY), 92);
        selectWire(scene, SIDING_ENTRY.getCenter(), wire);
        explain(scene, SIDING_ENTRY.getCenter(),
                "When the rails offer more than one route, sneak-right-click a rail on the leg you want to mark a waypoint.", 96);

        previewAndConfirm(scene, line.sidingHanger(), line.toSiding(), wire);
        drawBranch(scene, line.toSiding(), () -> true);
        explain(scene, line.sidingHanger().getCenter(), "Preview the span to the siding hanger, then sneak-right-click it again to confirm.", 92);

        selectWire(scene, line.sidingHanger().getCenter(), wire);
        scene.idle(14);
        scene.overlay().showOutline(PonderPalette.OUTPUT, SIDING_EXIT, util.select().position(SIDING_EXIT), 32);
        selectWire(scene, SIDING_EXIT.getCenter(), wire);
        scene.idle(24);
        previewAndConfirm(scene, line.eastHanger(), line.fromSiding(), wire);
        boolean[] exitConnected = { true };
        scene.addInstruction(active -> exitConnected[0] = true);
        drawBranch(scene, line.fromSiding(), () -> exitConnected[0]);
        explain(scene, line.eastHanger().getCenter(),
                "Mark the other leg and link back to the main line to close the loop. Both routes can carry current.", 94);

        scene.overlay().showOutline(PonderPalette.OUTPUT, EAST_RAIL, util.select().position(EAST_RAIL), 92);
        scene.overlay().showControls(EAST_RAIL.getCenter(), Pointing.DOWN, 40).rightClick().whileSneaking();
        explain(scene, EAST_RAIL.getCenter(),
                "To remove one route, sneak-right-click the highlighted rail with an empty hand to open Hanger Settings.", 92);
        preview(scene, line.fromSiding(), 68);
        scene.overlay().showText(108).pointAt(line.eastWire().center()).placeNearTarget().attachKeyFrame()
                .text("Use the arrows to highlight a span. Select Cut highlighted span and confirm. The main line stays connected.");
        scene.idle(68);
        scene.addInstruction(active -> exitConnected[0] = false);
        scene.idle(48);
        scene.markAsFinished();
    }

    static void feeders(SceneBuilder scene, SceneBuildingUtil util) {
        var line = showLayout(scene, util, "catenary_feeders", "Feeding the Overhead Network");
        showWires(scene, line.westWire(), line.eastWire());
        drawBranch(scene, line.toSiding(), () -> true);
        drawBranch(scene, line.fromSiding(), () -> true);

        var wire = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("powergrid", "insulated_copper_wire")));
        scene.world().showSection(util.select().position(WEST_FEED), Direction.DOWN);
        addFeed(scene, WEST_FEED, line.westHanger(), wire);
        explain(scene, line.westHanger().getCenter(),
                "Use normal right-clicks with Power Grid wire to feed any hanger. Match positive to positive and negative to negative.", 102);

        scene.world().showSection(util.select().position(EAST_FEED), Direction.DOWN);
        addFeed(scene, EAST_FEED, line.eastHanger(), wire);
        explain(scene, line.eastWire().center(),
                "More than one supply can feed the same line. How they share the load depends on voltage and wire resistance.", 103);
        explain(scene, EAST_FEED.getCenter(),
                "Unequal supply voltages can push current back into the lower-voltage supply.", 82);

        scene.overlay().showOutline(PonderPalette.GREEN, WEST_FEED, util.select().fromTo(WEST_FEED, line.westHanger()), 109);
        scene.overlay().showOutline(PonderPalette.GREEN, EAST_FEED, util.select().fromTo(EAST_FEED, line.eastHanger()), 109);
        explain(scene, line.sidingWire().center(),
                "Keep each supply, its wiring and its feeder hanger loaded. The line can carry power through intermediate chunks that unload.", 109);
        scene.markAsFinished();
    }

    private static Layout showLayout(SceneBuilder scene, SceneBuildingUtil util, String id, String title) {
        open(scene, id, title, 19, 0.49f);
        scene.setSceneOffsetY(-0.8f);
        var entry = curve(scene, new BlockPos(3, 1, 6), SIDING_ENTRY);
        var exit = curve(scene, SIDING_EXIT, new BlockPos(15, 1, 6));
        scene.world().showSection(util.select().fromTo(1, 1, 6, 17, 1, 11), Direction.DOWN);

        var west = mountHanger(scene, util, head(WEST_RAIL, 0), WIRE_HEIGHT);
        var east = mountHanger(scene, util, head(EAST_RAIL, 0), WIRE_HEIGHT);
        var siding = mountHanger(scene, util, head(SIDING_RAIL, 0), WIRE_HEIGHT);
        var sidingWire = head(SIDING_RAIL.above(WIRE_HEIGHT), 0);
        var toSiding = new ArrayList<>(sample(entry));
        toSiding.add(sidingWire);
        var fromSiding = new ArrayList<CatenarySpan.Head>();
        fromSiding.add(sidingWire);
        fromSiding.addAll(sample(exit));
        return new Layout(west, east, siding, head(WEST_RAIL.above(WIRE_HEIGHT), 0),
                head(EAST_RAIL.above(WIRE_HEIGHT), 0), sidingWire, toSiding, fromSiding);
    }

    private static List<CatenarySpan.Head> sample(BezierConnection curve) {
        var heads = new ArrayList<CatenarySpan.Head>();
        for (int i = 0; i <= 16; i++) heads.add(curveHead(curve, i / 16.0, WIRE_HEIGHT));
        return heads;
    }

    private static void selectWire(SceneBuilder scene, Vec3 target, ItemStack wire) {
        scene.overlay().showControls(target, Pointing.DOWN, 34).rightClick().whileSneaking().withItem(wire);
    }

    private static void previewAndConfirm(SceneBuilder scene, BlockPos hanger, List<CatenarySpan.Head> heads, ItemStack wire) {
        selectWire(scene, hanger.getCenter(), wire);
        preview(scene, heads, 33);
        scene.idle(33);
        selectWire(scene, hanger.getCenter(), wire);
        scene.idle(10);
        scene.effects().indicateSuccess(hanger);
    }

    private static void preview(SceneBuilder scene, List<CatenarySpan.Head> heads, int ticks) {
        for (int i = 1; i < heads.size(); i++) {
            var a = heads.get(i - 1);
            var b = heads.get(i);
            scene.overlay().showLine(PonderPalette.OUTPUT, a.supply(), b.supply(), ticks);
            scene.overlay().showLine(PonderPalette.INPUT, a.returnWire(), b.returnWire(), ticks);
        }
    }

    private static void drawBranch(SceneBuilder scene, List<CatenarySpan.Head> heads, BooleanSupplier visible) {
        geometry(scene, (level, beams) -> {
            if (!visible.getAsBoolean()) return;
            for (int i = 1; i < heads.size(); i++) {
                var a = heads.get(i - 1);
                var b = heads.get(i);
                beams.beam(a.supply(), b.supply(), 0.03125, COPPER);
                beams.beam(a.returnWire(), b.returnWire(), 0.03125, COPPER);
            }
        });
    }

    private static void addFeed(SceneBuilder scene, BlockPos supply, BlockPos hanger, ItemStack wire) {
        scene.overlay().showControls(hanger.getCenter(), Pointing.DOWN, 38).rightClick().withItem(wire);
        scene.idle(12);
        geometry(scene, (level, beams) -> {
            var state = level.getBlockState(supply);
            if (!(state.getBlock() instanceof IElectric source)
                    || !(level.getBlockEntity(hanger) instanceof CatenaryHangerBlockEntity endpoint)) return;
            for (int pin = 0; pin < 2; pin++) {
                var supplyPost = source.terminal(state, pin);
                var hangerPost = endpoint.terminal(level.getBlockState(hanger), pin);
                if (supplyPost == null || hangerPost == null) continue;
                var from = Vec3.atLowerCornerOf(supply).add(supplyPost.getOrigin());
                var to = Vec3.atLowerCornerOf(hanger).add(hangerPost.getOrigin());
                var middle = from.lerp(to, 0.5).add(0, -0.35, 0);
                beams.beam(from, middle, 0.048, LEAD_COLOR);
                beams.beam(middle, to, 0.048, LEAD_COLOR);
            }
        });
    }

    private record Layout(BlockPos westHanger, BlockPos eastHanger, BlockPos sidingHanger, CatenarySpan.Head westWire,
                          CatenarySpan.Head eastWire, CatenarySpan.Head sidingWire, List<CatenarySpan.Head> toSiding,
                          List<CatenarySpan.Head> fromSiding) {}
}
