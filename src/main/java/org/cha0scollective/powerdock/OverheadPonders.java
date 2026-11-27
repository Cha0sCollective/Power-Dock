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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

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
        var sill=straight.offset(0,1,2);
        var cap=sill.above(initialHeight+2);
        var custom=util.select().fromTo(sill,cap).add(util.select().fromTo(cap,cap.north(2)));
        scene.world().setBlocks(custom,net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE.defaultBlockState(),false);
        scene.world().showSection(custom,Direction.DOWN);
        explain(scene,cap.getCenter(),"Mount starts on Floating. Build a support up to the short crosspiece, or choose a mount in Hanger Settings.",105);
        scene.world().hideSection(custom,Direction.UP);
        var style=new FrameStyle(FrameStyle.Kind.CANTILEVER,1,0,0,false);
        scene.world().modifyBlockEntityNBT(util.select().position(hanger),CatenaryHangerBlockEntity.class,style::write);
        explain(scene,rail.center(),"Choose Cantilever pole and select a side. Reach moves the foot. A square portal has a separate reach for each foot.",100);
        var first=new TubeFitting("leg-right/flange-b",new Vec3(1,0,.30),0);
        var second=new TubeFitting("tie-right",new Vec3(1,0,.55),0);
        var origin=Vec3.atLowerCornerOf(hanger);
        var mount=HangerMount.fromTrack(hanger,rail,Direction.EAST);
        var head=mount.wireHead(hanger,initialHeight+2);
        var sample=new HangerFrame(new HangerFixture(head.center().subtract(origin),head.across(),
                mount.housing.add(0,2,0),mount.rail.y,9),style,new Vec3(Math.cos(mount.yaw),0,-Math.sin(mount.yaw)),
                List.of(), 0, null, mount.gridRail());
        var target=first.body(sample).start().add(origin);
        scene.overlay().showControls(target,Pointing.RIGHT,36).rightClick().withItem(
                new ItemStack(org.patryk3211.powergrid.collections.ModdedBlocks.WIRE_CONNECTOR.get()));
        scene.world().modifyBlockEntityNBT(util.select().position(hanger),CatenaryHangerBlockEntity.class,tag->{
            var list=new net.minecraft.nbt.ListTag();
            list.add(first.write(4));
            list.add(second.write(5));
            tag.put("TubeConnectors",list);
        });
        geometry(scene,(level,beams)->{
            if(!(level.getBlockEntity(hanger) instanceof CatenaryHangerBlockEntity block) || block.support().terminals()) return;
            for(int pin=0;pin<2;pin++) {
                var fitting=block.terminal(block.getBlockState(),4+pin);
                if(fitting==null) continue;
                var end=origin.add(fitting.getOrigin());
                var top=origin.add(block.terminal(block.getBlockState(),pin).getOrigin());
                beams.beam(end,top,.023,COPPER);
            }
        });
        explain(scene,target,"Right-click exposed steel with a Power Grid Wire Connector, even on a brace. Wire each conductor through its own fittings to the matching head terminal.",126);
        scene.world().modifyBlockEntityNBT(util.select().position(hanger),CatenaryHangerBlockEntity.class,tag->{
            tag.remove("TubeConnectors"); tag.putBoolean("FeedPosts",true);
        });
        var foot=sample.member("leg-right").start().add(origin);
        scene.overlay().showControls(foot.add(0,.7,0),Pointing.RIGHT,40).rightClick().withItem(linkingWire());
        explain(scene,foot.add(0,.7,0),"For a shorter hookup, enable Feed posts. Wire your supply to the positive and negative posts at the foot. Each feeds its matching overhead terminal.",118);
        scene.markAsFinished();
    }

    static void clippedLeads(SceneBuilder scene, SceneBuildingUtil util) {
        open(scene, "catenary_clipped_leads", "Clipping Feeder Leads", 13, 0.76f);
        var railPos = new BlockPos(3, 1, 4);
        var rail = head(railPos, 0);
        var height = HangerTrackTarget.INITIAL_HEIGHT;
        scene.world().showSection(util.select().fromTo(1, 1, 4, 5, 1, 4), Direction.DOWN);
        var hanger = mountHanger(scene, util, rail, height);
        var selection = util.select().position(hanger);
        var style = new FrameStyle(FrameStyle.Kind.CANTILEVER, 1, 0, 0, false);
        scene.world().modifyBlockEntityNBT(selection, CatenaryHangerBlockEntity.class, style::write);
        var mount = HangerMount.fromTrack(hanger, rail, Direction.EAST);
        var origin = Vec3.atLowerCornerOf(hanger);
        var head = mount.wireHead(hanger, height);
        var fixture = new HangerFixture(head.center().subtract(origin), head.across(), mount.housing, mount.rail.y, 7);
        var withPosts = style.change(14);
        var frame = new HangerFrame(fixture, withPosts, head.across(), List.of(), 3,
                new Vec3[]{mount.post(0).getOrigin(), mount.post(1).getOrigin()}, mount.gridRail());
        var pole = frame.member("leg-right/flange-b").point(1, 0, .4).add(origin);
        var arm = frame.member("boom").point(0, 1, .55).add(origin);
        var footPost = frame.post(0).add(origin);

        explain(scene, pole, "Choose a Cantilever pole or Square portal and start with Feed posts off.", 92);
        var wire = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:insulated_copper_wire")), 16);
        scene.overlay().showControls(pole, Pointing.RIGHT, 38).rightClick().whileSneaking().withItem(wire);
        scene.idle(14);
        showClippedLeads(scene, util, hanger, 1);
        explain(scene, pole, "Hold a stack of Insulated Copper Wire and sneak-right-click the pole or arm to fit the positive lead.", 103);

        scene.overlay().showControls(arm, Pointing.DOWN, 40).rightClick().whileSneaking().withItem(wire);
        scene.idle(14);
        showClippedLeads(scene, util, hanger, 3);
        explain(scene, arm, "Repeat for the negative lead. Both leads run up to their matching terminals.", 94);

        var supply = new BlockPos(5, 1, 7);
        var source = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powergrid:creative_voltage_source"));
        scene.world().setBlock(supply, source.defaultBlockState(), false);
        scene.world().showSection(util.select().position(supply), Direction.DOWN);
        scene.overlay().showControls(footPost, Pointing.RIGHT, 40).rightClick().withItem(wire);
        scene.idle(14);
        CatenaryNetworkPonders.drawFeed(scene, supply, hanger, 2);
        explain(scene, footPost, "Use regular wire clicks to connect the supply to the matching positive and negative posts at the foot.", 102);

        var clip = frame.member("clip-negative").point(0, 0, .5).add(origin);
        var cutters = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:wire_cutter")));
        scene.overlay().showControls(clip, Pointing.RIGHT, 40).rightClick().withItem(cutters);
        scene.idle(14);
        showClippedLeads(scene, util, hanger, 1);
        explain(scene, clip, "Right-click a lead or its clip with Power Grid Wire Cutters to remove just that lead.", 108);
        scene.markAsFinished();
    }

    private static void showClippedLeads(SceneBuilder scene, SceneBuildingUtil util, BlockPos hanger, int mask) {
        scene.world().modifyBlockEntityNBT(util.select().position(hanger), CatenaryHangerBlockEntity.class, tag -> {
            tag.putBoolean("FeedPosts", true);
            tag.putBoolean("ClippedFeed", true);
            var leads = new ListTag();
            for (int pin = 0; pin < 2; pin++) {
                if ((mask & (1 << pin)) == 0) continue;
                var lead = new CompoundTag();
                lead.putInt("Pin", pin);
                lead.putUUID("Wire", new UUID(0, pin + 1));
                lead.putInt("Count", 1);
                leads.add(lead);
            }
            tag.put("ClippedFeedWires", leads);
        });
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
            FrameStyle.FLOATING.write(tag);
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
        var frame=new HangerFrame(fixture, FrameStyle.PORTAL, fixture.contactSpacing(),
                List.of(), 0, null, new Vec3(wires.x, fixture.railY(), wires.z));
        geometry(scene, (level, beams) -> CatenaryRenderer.drawFrame(beams, frame));
    }

    static void showWires(SceneBuilder scene, CatenarySpan.Head from, CatenarySpan.Head to) {
        geometry(scene, (level, beams) -> {
            beams.beam(from.supply(), to.supply(), 0.03125, COPPER);
            beams.beam(from.returnWire(), to.returnWire(), 0.03125, COPPER);
        });
    }
}
