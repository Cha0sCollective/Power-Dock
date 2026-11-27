package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.patryk3211.powergrid.collections.ModdedBlocks;

@EventBusSubscriber(modid = PowerDock.ID, value = Dist.CLIENT)
final class FrameSelection {
    private static FrameInteraction.Hit target;
    private static HangerTrackTarget placement;
    private static Direction facing;
    private static HangerFrame preview;
    private static HangerMount previewMount;
    static void pose(PoseStack pose, HangerFrame.Member body) {
        var origin = body.start();
        pose.translate(origin.x, origin.y, origin.z);
        var basis = new Matrix3f().setColumn(0, body.u().toVector3f()).setColumn(1, body.axis().toVector3f())
                .setColumn(2, body.v().scale(-1).toVector3f());
        pose.mulPose(new Quaternionf().setFromNormalized(basis));
    }
    static void renderConnectors(CatenaryHangerBlockEntity hanger, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        var state = ModdedBlocks.WIRE_CONNECTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN);
        for (var entry : hanger.wiring.bodies(hanger.frame()).entrySet()) {
            if (entry.getKey()<FrameWiring.FIRST_CONNECTOR) continue;
            pose.pushPose();
            pose(pose, entry.getValue());
            pose.translate(-.5, 0, -.5);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, light, overlay);
            pose.popPose();
        }
    }
    @SubscribeEvent
    public static void select(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        HangerTrackTarget next = null;
        if (mc.player != null && mc.screen == null && !mc.options.hideGui && mc.player.getMainHandItem().is(PowerDock.CATENARY_HANGER.get().asItem()))
            next = placement(mc);
        if (next == null) { placement = null; preview = null; }
        else if (!next.equals(placement) || facing != mc.player.getDirection()) {
            placement = next;
            facing = mc.player.getDirection();
            var pos = next.overheadPosition();
            previewMount = HangerMount.fromTrack(pos, next.frame(), facing);
            var head = previewMount.wireHead(pos, HangerTrackTarget.INITIAL_HEIGHT);
            var fixture = new HangerFixture(head.center().subtract(Vec3.atLowerCornerOf(pos)), head.across(), previewMount.housing, previewMount.rail.y, 7);
            preview = new HangerFrame(fixture, FrameStyle.FLOATING, head.across());
        }
        target = mc.screen != null || mc.player == null || mc.options.hideGui ? null :
                FrameInteraction.pick(mc.player, mc.player.getMainHandItem());
    }
    private static HangerTrackTarget placement(Minecraft mc) {
        var level = mc.level;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && !Sable.HELPER.isInPlotGrid(level, hit.getBlockPos())) {
            var rail = hit.getBlockPos();
            var state = level.getBlockState(rail);
            if (state.getBlock() instanceof ITrackBlock track) {
                var axes = track.getTrackAxes(level, rail, state);
                if (axes.size() != 1) return null;
                var center = Vec3.atBottomCenterOf(rail).add(0, track.getElevationAtCenter(level, rail, state), 0);
                return available(level, rail, center, axes.getFirst());
            }
        }
        var selected = TrackBlockOutline.result;
        if (selected == null || selected.blockEntity().getLevel() != level || selected.blockEntity().isRemoved()) return null;
        var curve = selected.blockEntity().getConnections().get(selected.loc().curveTarget());
        var ray = HangerTrackTarget.trace(level, mc.player);
        if (curve == null || ray == null) return null;
        var aim = HangerTrackTarget.aimAtCurve(curve, ray.eye(), ray.direction(), ray.visible());
        if (aim == null) return null;
        var rail = BlockPos.containing(aim.point());
        double t = CatenaryRoute.closestParameter(curve, Vec3.atBottomCenterOf(rail));
        return available(level, rail, curve.getPosition(t), curve.getPosition(Math.min(1, t+.001)).subtract(curve.getPosition(Math.max(0, t-.001))));
    }
    private static HangerTrackTarget available(Level level, BlockPos rail, Vec3 center, Vec3 direction) {
        var result = new HangerTrackTarget(rail, CatenarySpan.Head.along(center, direction));
        return level.getBlockState(result.overheadPosition()).isAir() && HangerTrackTarget.mountedHanger(level, rail, center) == null ? result : null;
    }
    @SubscribeEvent
    public static void label(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (target == null || (target.slot() == -1 || target.slot() == 0 || target.slot() == 1) || mc.options.hideGui || mc.screen != null) return;
        String key = target.slot() == 2 ? "positive" : target.slot() == 3 ? "negative" : "connector";
        if (target.slot()==FrameInteraction.INSTALL) {
            key = switch(target.hanger().clipped.next()) {case 0 -> "lay_positive"; case 1 -> "lay_negative"; default -> "both_laid";};
        } else if (target.slot()<=FrameInteraction.CUT_FIRST) key=target.slot()==FrameInteraction.CUT_FIRST?"cut_positive":"cut_negative";
        var label = Component.translatable("gui.powerdock.fitting_"+key, target.slot()-3);
        var g = event.getGuiGraphics();
        g.drawCenteredString(mc.font, label, g.guiWidth()/2, g.guiHeight()/2+12, 0xffeee9db);
    }
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (preview != null && mc.screen == null && !mc.options.hideGui) {
            var pose = event.getPoseStack();
            var delta = Vec3.atLowerCornerOf(placement.overheadPosition()).subtract(event.getCamera().getPosition());
            var lines = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
            pose.pushPose();
            pose.translate(delta.x, delta.y, delta.z);
            for (var part : preview.members) {
                pose.pushPose();
                pose(pose, part);
                LevelRenderer.renderLineBox(pose, lines, new AABB(-part.halfU(), 0, -part.halfV(), part.halfU(), part.length(), part.halfV()),
                        .75f, .85f, .72f, .7f);
                pose.popPose();
            }
            for (var box : previewMount.shape.toAabbs()) LevelRenderer.renderLineBox(pose, lines, box, .75f, .85f, .72f, .7f);
            pose.popPose();
        }
        if (target == null) return;
        if (mc.level != target.hanger().getLevel() || mc.screen != null || mc.options.hideGui) return;
        var part = target.slot()==-1 ? TubeFitting.on(target.member(), target.local()).body(target.hanger().frame()) : target.member();
        if (part == null) return;
        var offset = Vec3.atLowerCornerOf(target.hanger().getBlockPos()).subtract(event.getCamera().getPosition());
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(offset.x, offset.y, offset.z);
        pose(pose, part);
        var bounds = new AABB(-part.halfU(), 0, -part.halfV(), part.halfU(), part.length(), part.halfV()).inflate(.003);
        LevelRenderer.renderLineBox(pose, mc.renderBuffers().bufferSource().getBuffer(RenderType.lines()),
                bounds, target.slot() == 3 ? .3f : 1f, target.slot() == 2 ? .35f : .8f, target.slot() == 3 ? 1f : .35f, 1);
        pose.popPose();
    }
}
