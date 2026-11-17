package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

@EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PowerDockRenderer implements BlockEntityRenderer<PowerDockBlockEntity> {
    private static final ModelResourceLocation ARM = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/power_dock_arm"));
    private static final ModelResourceLocation SHIP_ARM = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "block/power_dock_ship_arm"));
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
        event.register(SHIP_ARM);
    }

    @Override
    public void render(PowerDockBlockEntity dock, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(PowerDockBlock.modelRotation(dock.facing()));
        pose.translate(-0.5, -0.5, -0.5 - dock.armProgress(partialTick) * RENDER_TRAVEL);
        // Viewed from the mating face, the two halves need opposite cable layouts.
        var arm = dock.hasLevel() && Sable.HELPER.isInPlotGrid(dock) ? SHIP_ARM : ARM;
        var model = Minecraft.getInstance().getModelManager().getModel(arm);
        modelRenderer.renderModel(pose.last(), buffers.getBuffer(Sheets.solidBlockSheet()), dock.getBlockState(),
                model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.solid());
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(PowerDockBlockEntity dock) {
        var facing = dock.facing();
        return new AABB(dock.getBlockPos()).expandTowards(facing.getStepX() * RENDER_TRAVEL,
                facing.getStepY() * RENDER_TRAVEL, facing.getStepZ() * RENDER_TRAVEL);
    }
}
