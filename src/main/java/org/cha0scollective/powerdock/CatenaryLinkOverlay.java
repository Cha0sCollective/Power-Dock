package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = PowerDock.ID, value = Dist.CLIENT)
final class CatenaryLinkOverlay {
    private static final int SUPPLY = 0xffffac67;
    private static final int RETURN = 0xff6de5d5;
    private static final int CONFLICT = 0xffff6853;
    @Nullable private static CatenaryWireUse.ClientPreview preview;
    @Nullable private static Component status;

    private CatenaryLinkOverlay() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var selection = mc.player == null || mc.level == null || mc.player.isSpectator() ? null
                : CatenaryWireUse.activeSelection(mc.player.getMainHandItem(), mc.level);
        preview = selection == null ? null : CatenaryWireUse.clientPreview(mc.level, selection);
        status = selection == null ? null : Component.translatable("gui.powerdock.catenary_" +
                (selection.getBoolean("Unlink") ? "confirm_unlink" : selection.getBoolean("Conflict") ? "conflict"
                        : preview != null ? "confirm_span"
                        : selection.getBoolean("NeedsWaypoint") ? "choose_waypoint" : "next_hanger"));
    }

    @SubscribeEvent
    public static void renderStatus(RenderGuiLayerEvent.Post event) {
        if (status == null || !event.getName().equals(VanillaGuiLayers.HOTBAR)) return;
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) return;
        var graphics = event.getGuiGraphics();
        int center = graphics.guiWidth() / 2;
        int top = graphics.guiHeight() - 79;
        graphics.drawCenteredString(mc.font, status, center, top, SUPPLY);
        graphics.drawCenteredString(mc.font, Component.translatable("gui.powerdock.catenary_cancel"), center, top + 11, 0xffece8df);
    }

    @SubscribeEvent
    public static void renderPreview(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || mc.getCameraEntity() != mc.player) return;
        var displayed = mc.screen instanceof HangerSettingsScreen settings
                ? CatenaryWireUse.menuPreview(mc.level, settings.getMenu().containerId) : mc.screen == null ? preview : null;
        if (displayed == null) return;
        var origin = event.getCamera().getPosition();
        var pose = event.getPoseStack().last();
        var buffer = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        for (var span : displayed.spans()) {
            var points = span.points;
            for (int i = 1; i < points.size(); i++) {
                var from = points.get(i - 1);
                var to = points.get(i);
                wire(buffer, pose, from.supply().subtract(origin), to.supply().subtract(origin), displayed.conflict() ? CONFLICT : SUPPLY);
                wire(buffer, pose, from.returnWire().subtract(origin), to.returnWire().subtract(origin), displayed.conflict() ? CONFLICT : RETURN);
            }
        }
    }

    private static void wire(VertexConsumer buffer, PoseStack.Pose pose, Vec3 from, Vec3 to, int color) {
        var tangent = to.subtract(from).normalize();
        buffer.addVertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z).setColor(color)
                .setNormal(pose, (float) tangent.x, (float) tangent.y, (float) tangent.z);
        buffer.addVertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z).setColor(color)
                .setNormal(pose, (float) tangent.x, (float) tangent.y, (float) tangent.z);
    }
}
