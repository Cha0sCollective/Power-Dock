package org.cha0scollective.powerdock;

import com.mojang.math.Axis;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

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
            var selected = HangerRailTarget.onStraight(level, pos);
            return selected == null ? null : new Target(level, selected, pos, null);
        }

        var curveHit = TrackBlockOutline.result;
        if (curveHit == null || curveHit.blockEntity().isRemoved() || curveHit.blockEntity().getLevel() != level) return null;
        var curve = curveHit.blockEntity().getConnections().get(curveHit.loc().curveTarget());
        if (curve == null) return null;
        var ray = HangerTrackTarget.trace(level, player);
        if (ray == null) return null;
        var selected = HangerRailTarget.onCurve(level, curve, ray);
        return selected == null ? null : new Target(level, selected, null, curveHit);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useCurvedRail(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (mc.screen != null || mc.level == null || player == null || mc.getCameraEntity() != player
                || player.isSpectator() || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) return;
        var selected = select(mc, mc.level, player);
        if (selected != null && selected.curve != null) {
            PacketDistributor.sendToServer(HangerRailControls.OpenFromRail.INSTANCE);
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || target == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level != target.level || mc.screen != null || mc.options.hideGui || !target.isStillAimedAt(mc)) return;
        var center = target.rail.center().subtract(event.getCamera().getPosition());
        var direction = target.rail.direction();
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(center.x, center.y, center.z);
        pose.mulPose(Axis.YP.rotation((float) Math.atan2(direction.x, direction.z)));
        pose.mulPose(Axis.XP.rotation((float) -Math.atan2(direction.y, direction.horizontalDistance())));
        LevelRenderer.renderLineBox(pose, mc.renderBuffers().bufferSource().getBuffer(RenderType.lines()),
                target.rail.outline(), 1f, 0.72f, 0.24f, 0.95f);
        pose.popPose();
    }

    private record Target(ClientLevel level, HangerRailTarget rail,
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
