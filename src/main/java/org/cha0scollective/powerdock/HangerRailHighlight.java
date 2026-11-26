package org.cha0scollective.powerdock;

import com.mojang.math.Axis;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = PowerDock.ID, value = Dist.CLIENT)
final class HangerRailHighlight {
    @Nullable private static Target target;

    private HangerRailHighlight() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        target = mc.level == null || mc.player == null || mc.player.isSpectator()
                || mc.screen != null || mc.options.hideGui || mc.getCameraEntity() != mc.player
                ? null : select(mc, mc.level, mc.player);
    }

    @Nullable
    private static Target select(Minecraft mc, ClientLevel level, LocalPlayer player) {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ITrackBlock track)) return null;
            var axes = track.getTrackAxes(level, pos, state);
            if (axes.size() != 1) return null;
            var rail = Vec3.atBottomCenterOf(pos).add(0, track.getElevationAtCenter(level, pos, state), 0);
            if (HangerTrackTarget.mountedHanger(level, pos, rail) == null) return null;
            var direction = axes.getFirst();
            return new Target(level, rail, direction, direction.length() * 0.5, pos, null);
        }

        var curveHit = TrackBlockOutline.result;
        if (curveHit == null || curveHit.blockEntity().isRemoved() || curveHit.blockEntity().getLevel() != level) return null;
        var curve = curveHit.blockEntity().getConnections().get(curveHit.loc().curveTarget());
        if (curve == null) return null;
        var eye = player.getEyePosition();
        var direction = player.calculateViewVector(player.getXRot(), player.getYRot());
        var end = eye.add(direction.scale(player.blockInteractionRange()));
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        var aim = HangerTrackTarget.aimAtCurve(curve, eye, direction, hit.getLocation().distanceTo(eye));
        if (aim == null || HangerTrackTarget.mountedHanger(level, BlockPos.containing(aim.point()), aim.point()) == null) return null;
        return new Target(level, curveHit.vec(), curveHit.direction(), 0.5, null, curveHit);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || target == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level != target.level || mc.screen != null || mc.options.hideGui || !target.isStillAimedAt(mc)) return;
        var center = target.center.subtract(event.getCamera().getPosition());
        var direction = target.direction;
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(center.x, center.y, center.z);
        pose.mulPose(Axis.YP.rotation((float) Math.atan2(direction.x, direction.z)));
        pose.mulPose(Axis.XP.rotation((float) -Math.atan2(direction.y, direction.horizontalDistance())));
        var bounds = new AABB(-0.515625, -0.03125, -target.halfLength, 0.515625, 0.1875, target.halfLength);
        LevelRenderer.renderLineBox(pose, mc.renderBuffers().bufferSource().getBuffer(RenderType.lines()),
                bounds, 1f, 0.72f, 0.24f, 0.95f);
        pose.popPose();
    }

    private record Target(ClientLevel level, Vec3 center, Vec3 direction, double halfLength,
                          @Nullable BlockPos straight, @Nullable TrackBlockOutline.BezierPointSelection curve) {
        boolean isStillAimedAt(Minecraft mc) {
            if (straight != null) {
                return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                        && straight.equals(hit.getBlockPos());
            }
            var current = TrackBlockOutline.result;
            return current != null && curve != null && current.blockEntity() == curve.blockEntity()
                    && current.loc().equals(curve.loc());
        }
    }
}
