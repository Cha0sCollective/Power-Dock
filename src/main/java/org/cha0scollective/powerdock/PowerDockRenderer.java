package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PowerDockRenderer implements BlockEntityRenderer<PowerDockBlockEntity> {
    private static final ModelResourceLocation ARM = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/power_dock_arm"));
    private static final ModelResourceLocation PIN_ARM = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/pin_dock_arm"));
    private static final ModelResourceLocation POWER_HEAD = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/power_dock_head"));
    private static final ModelResourceLocation PIN_HEAD = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/pin_dock_head"));
    private static final ModelResourceLocation ADAPTIVE_STEM = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/adaptive_dock_stem"));
    // A small visual overlap hides the seam when the physics joint settles.
    private static final double RENDER_TRAVEL = PowerDockBlockEntity.ARM_TRAVEL + 1.0 / 128;
    private final ModelBlockRenderer modelRenderer;

    private PowerDockRenderer(BlockEntityRendererProvider.Context context) {
        modelRenderer = context.getBlockRenderDispatcher().getModelRenderer();
    }

    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(PowerDock.DOCK_ENTITY.get(), PowerDockRenderer::new);
    }

    @SubscribeEvent
    public static void registerModel(ModelEvent.RegisterAdditional event) {
        event.register(ARM);
        event.register(PIN_ARM);
        event.register(POWER_HEAD);
        event.register(PIN_HEAD);
        event.register(ADAPTIVE_STEM);
    }

    @Override
    public boolean shouldRender(PowerDockBlockEntity dock, Vec3 camera) {
        return dock.drawsMovingArm() && BlockEntityRenderer.super.shouldRender(dock, camera);
    }

    @Override
    public void render(PowerDockBlockEntity dock, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (!dock.drawsMovingArm()) return;
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(PowerDockBlock.modelRotation(dock.facing()));
        if (dock.adaptivePartner() != null || dock.lastAdaptiveArm != null) {
            renderAdaptiveArm(dock, partialTick, pose, buffers, light, overlay);
        } else {
            pose.translate(-0.5, -0.5, -0.5 - dock.armProgress(partialTick) * RENDER_TRAVEL);
            renderModel(dock.isPinDock() ? PIN_ARM : ARM, dock, pose, buffers, light, overlay);
        }
        pose.popPose();
    }

    private void renderAdaptiveArm(PowerDockBlockEntity dock, float partialTick, PoseStack pose,
                                   MultiBufferSource buffers, int light, int overlay) {
        if (dock.getBlockState().getValue(PowerDockBlock.DEPLOYED)) {
            var updated = adaptivePose(dock, partialTick);
            if (updated != null) dock.lastAdaptiveArm = updated;
        }
        float progress = dock.armProgress(partialTick);
        var offset = new Vector3d();
        var headRotation = new Quaternionf();
        if (dock.lastAdaptiveArm != null) {
            offset.set(dock.lastAdaptiveArm.offset()).mul(progress);
            var target = dock.lastAdaptiveArm.headRotation();
            if (Math.abs(target.w) < 0.05f) {
                // Give a half-turn a consistent direction even when the hulls rock slightly.
                float sign = Math.copySign(1, target.y);
                float angle = (float) (2 * Math.acos(sign * target.w));
                headRotation.rotationAxis(angle * progress, sign * target.x, sign * target.y, sign * target.z);
            } else {
                headRotation.slerp(target, progress);
            }
        }
        offset.z -= 0.5; // the head starts at the block face

        pose.pushPose();
        pose.translate(offset.x, offset.y, offset.z);
        pose.mulPose(headRotation);
        pose.translate(-0.5, -0.5, 0);
        renderModel(dock.isPinDock() ? PIN_HEAD : POWER_HEAD, dock, pose, buffers, light, overlay);
        pose.popPose();

        // Run a telescoping stem from the back of the contact head into the housing.
        var rear = headRotation.transform(new Vector3f(0, 0, 2f / 16))
                .add((float) offset.x, (float) offset.y, (float) offset.z);
        var stem = new Vector3f(0, 0, 1f / 16).sub(rear);
        float length = stem.length();
        pose.pushPose();
        pose.translate(rear.x, rear.y, rear.z);
        pose.mulPose(new Quaternionf().rotationTo(0, 0, 1, stem.x / length, stem.y / length, stem.z / length));
        pose.scale(1, 1, length);
        pose.translate(-0.5, -0.5, 0);
        renderModel(ADAPTIVE_STEM, dock, pose, buffers, light, overlay);
        pose.popPose();
    }

    @Nullable
    private static PowerDockBlockEntity.AdaptiveArmPose adaptivePose(PowerDockBlockEntity dock, float partialTick) {
        var partner = dock.adaptivePartner();
        if (partner == null || !(dock.getLevel().getBlockEntity(partner) instanceof PowerDockBlockEntity other)
                || !dock.getBlockState().is(other.getBlockState().getBlock())) return null;

        var local = SableCompanion.INSTANCE.getContainingClient(dock);
        var remote = SableCompanion.INSTANCE.getContainingClient(other);
        var otherFace = other.anchor(0.5);
        var otherRotation = new Quaterniond(PowerDockBlock.modelRotation(other.facing())).rotateY(Math.PI);
        if (remote != null) {
            var pose = remote.renderPose(partialTick);
            pose.transformPosition(otherFace);
            otherRotation.premul(pose.orientation());
        }
        if (local != null) {
            var pose = local.renderPose(partialTick);
            pose.transformPositionInverse(otherFace);
            otherRotation.premul(new Quaterniond(pose.orientation()).invert());
        }
        var ownRotation = new Quaterniond(PowerDockBlock.modelRotation(dock.facing()));
        var offset = ownRotation.transformInverse(otherFace.sub(dock.anchor(0.5))).mul(0.5);
        // A pending release packet may arrive after the hull has already moved away.
        if (!(offset.z <= 0 && offset.lengthSquared() <= 1)) return null;
        // One face defines the keyed contact plane. The other head can also turn around its axis on roof docks.
        boolean twist = Long.compareUnsigned(dock.getBlockPos().asLong(), partner.asLong()) > 0;
        var rotation = twist ? new Quaternionf(ownRotation.invert().mul(otherRotation)).normalize() : new Quaternionf();
        return new PowerDockBlockEntity.AdaptiveArmPose(offset, rotation);
    }

    private void renderModel(ModelResourceLocation location, PowerDockBlockEntity dock, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        var model = Minecraft.getInstance().getModelManager().getModel(location);
        modelRenderer.renderModel(pose.last(), buffers.getBuffer(Sheets.solidBlockSheet()), dock.getBlockState(),
                model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.solid());
    }

    @Override
    public AABB getRenderBoundingBox(PowerDockBlockEntity dock) {
        if (dock.adaptivePartner() != null || dock.lastAdaptiveArm != null) return new AABB(dock.getBlockPos()).inflate(1);
        var facing = dock.facing();
        return new AABB(dock.getBlockPos()).expandTowards(facing.getStepX() * RENDER_TRAVEL,
                facing.getStepY() * RENDER_TRAVEL, facing.getStepZ() * RENDER_TRAVEL);
    }
}
