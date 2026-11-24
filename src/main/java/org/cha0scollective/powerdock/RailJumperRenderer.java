package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Vector3d;

@EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RailJumperRenderer implements BlockEntityRenderer<RailJumperBlockEntity> {
    private static final ResourceLocation CABLE_SHEATH =
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "textures/block/jumper_sheath.png");
    private static final RenderType CABLE = RenderType.entityCutoutNoCull(CABLE_SHEATH);
    private static final int SIDES = 6;
    private static final double RADIUS = 0.055;
    private static final double[] COS = new double[SIDES + 1];
    private static final double[] SIN = new double[SIDES + 1];

    static {
        for (int i = 0; i <= SIDES; i++) {
            double angle = i * Math.TAU / SIDES;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
        }
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(PowerDock.JUMPER_ENTITY.get(), context -> new RailJumperRenderer());
    }

    @Override
    public boolean shouldRender(RailJumperBlockEntity jumper, Vec3 camera) {
        return jumper.renderPartner() != null && BlockEntityRenderer.super.shouldRender(jumper, camera);
    }

    @Override
    public void render(RailJumperBlockEntity jumper, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        var level = jumper.getLevel();
        var partner = jumper.renderPartner();
        if (partner == null || level == null || !level.hasChunkAt(partner)
                || !(level.getBlockEntity(partner) instanceof RailJumperBlockEntity other)
                || !other.getBlockState().getValue(RailJumperBlock.CONNECTED)) return;

        var fromHull = SableCompanion.INSTANCE.getContainingClient(jumper);
        var toHull = SableCompanion.INSTANCE.getContainingClient(other);
        if (fromHull == null || toHull == null) return;
        var fromPose = fromHull.renderPose(partialTick);
        var toPose = toHull.renderPose(partialTick);
        var start = fromPose.transformPosition(socket(jumper, 0.5));
        var end = toPose.transformPosition(socket(other, 0.5));
        double span = start.distance(end);
        if (!(span > 0.02)) return;

        // Cubic tangents leave the strain reliefs straight. The sag is in world space.
        double stub = Math.clamp(span * 0.23, 0.15, 0.55);
        var startControl = fromPose.transformPosition(socket(jumper, 0.5 + stub));
        var endControl = toPose.transformPosition(socket(other, 0.5 + stub));
        double sag = Math.clamp(span * 0.14, 0.08, 0.42);
        int segments = Math.clamp((int) Math.ceil(span * 8), 8, 24);
        var points = new Vector3d[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double t0 = 1 - t;
            var point = new Vector3d(start).mul(t0 * t0 * t0)
                    .fma(3 * t0 * t0 * t, startControl)
                    .fma(3 * t0 * t * t, endControl)
                    .fma(t * t * t, end);
            double bow = 4 * t0 * t;
            point.y -= sag * bow * bow;
            points[i] = fromPose.transformPositionInverse(point)
                    .sub(jumper.getBlockPos().getX(), jumper.getBlockPos().getY(), jumper.getBlockPos().getZ());
        }

        var up = fromPose.orientation().transformInverse(new Vector3d(0, 1, 0));
        var consumer = buffers.getBuffer(CABLE);
        var ring = ring(points, 0, up);
        float v = 0;
        for (int i = 1; i <= segments; i++) {
            var next = ring(points, i, up);
            float nextV = v + (float) (points[i].distance(points[i-1]) * 2);
            for (int side = 0; side < SIDES; side++) {
                vertex(consumer, pose.last(), ring, side, v, light, overlay);
                vertex(consumer, pose.last(), ring, side + 1, v, light, overlay);
                vertex(consumer, pose.last(), next, side + 1, nextV, light, overlay);
                vertex(consumer, pose.last(), next, side, nextV, light, overlay);
            }
            ring = next;
            v = nextV;
        }
    }

    private static Vector3d socket(RailJumperBlockEntity jumper, double offset) {
        var facing = jumper.facing();
        var pos = jumper.getBlockPos();
        return new Vector3d(pos.getX() + 0.5 + facing.getStepX() * offset, pos.getY() + 6.0 / 16,
                pos.getZ() + 0.5 + facing.getStepZ() * offset);
    }

    private static Ring ring(Vector3d[] points, int i, Vector3d up) {
        var tangent = points[Math.min(i + 1, points.length - 1)].sub(points[Math.max(i - 1, 0)], new Vector3d());
        if (tangent.lengthSquared() < 1e-8) tangent.set(0, 0, 1);
        tangent.normalize();
        var right = tangent.cross(up, new Vector3d());
        if (right.lengthSquared() < 1e-6) tangent.cross(new Vector3d(1, 0, 0), right);
        right.normalize();
        return new Ring(points[i], right, right.cross(tangent, new Vector3d()));
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Ring ring, int side, float v, int light, int overlay) {
        var center = ring.point;
        double nx = ring.right.x * COS[side] + ring.up.x * SIN[side];
        double ny = ring.right.y * COS[side] + ring.up.y * SIN[side];
        double nz = ring.right.z * COS[side] + ring.up.z * SIN[side];
        buffer.addVertex(pose, (float) (center.x + nx * RADIUS), (float) (center.y + ny * RADIUS),
                        (float) (center.z + nz * RADIUS))
                .setColor(255, 255, 255, 255).setUv((float) side / SIDES, v)
                .setOverlay(overlay).setLight(light).setNormal(pose, (float) nx, (float) ny, (float) nz);
    }

    @Override
    public AABB getRenderBoundingBox(RailJumperBlockEntity jumper) {
        return new AABB(jumper.getBlockPos()).inflate(6);
    }

    private record Ring(Vector3d point, Vector3d right, Vector3d up) {}
}
