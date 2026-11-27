package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
final class CatenaryRenderer {
    private static final RenderType SOLID =
            RenderType.entityCutoutNoCull(ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "textures/block/catenary_solid.png"));
    private static final int COPPER = 0xFFA76548;
    private static final int INSULATED_COPPER = 0xFF42423F;
    private static final int METAL = 0xFF485650;
    private static final int STEEL = 0xFF65736D;
    private static final int CARBON = 0xFF2C3332;
    private static final int CERAMIC = 0xFFB9B292;

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PowerDock.CLIPPED_LEAD.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerBlockEntityRenderer(PowerDock.CATENARY_HANGER_ENTITY.get(), unused -> new Hanger());
        event.registerBlockEntityRenderer(PowerDock.PANTOGRAPH_ENTITY.get(), unused -> new Pickup());
    }

    private static final class Hanger implements BlockEntityRenderer<CatenaryHangerBlockEntity> {
        @Override
        public void render(CatenaryHangerBlockEntity hanger, float partialTick, PoseStack pose,
                           MultiBufferSource buffers, int light, int overlay) {
            var out = beams(pose, buffers, light, overlay);
            var frame = hanger.frame();
            if (frame != null) {
                drawFrame(out, frame);
                FrameSelection.renderConnectors(hanger,pose,buffers,light,overlay);
            }
            var mount = hanger.mount();
            if (mount != null && hanger.getBlockState().getValue(CatenaryBlock.TRACK_ALIGNED)) {
                var renderer = Minecraft.getInstance().getBlockRenderer();
                var state = PowerDock.CATENARY_HANGER.get().defaultBlockState();
                var housing = frame.housing;
                pose.pushPose();
                pose.translate(housing.x, housing.y, housing.z);
                pose.mulPose(Axis.YP.rotation((float) mount.yaw));
                pose.translate(-0.5, 0, -0.5);
                renderer.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.solidBlockSheet()),
                        state, renderer.getBlockModel(state), 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.solid());
                pose.popPose();
            }
        }

        @Override public int getViewDistance() { return 96; }

        @Override
        public AABB getRenderBoundingBox(CatenaryHangerBlockEntity hanger) {
            return new AABB(hanger.getBlockPos()).inflate(17, 24, 17);
        }
    }

    @EventBusSubscriber(modid = PowerDock.ID, value = Dist.CLIENT)
    static final class OverheadWires {
        private static final Map<CatenarySpan, WireMesh> MESHES = new WeakHashMap<>();

        @SubscribeEvent
        public static void render(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            var game = Minecraft.getInstance();
            var level = game.level;
            if (level == null) return;
            var camera = event.getCamera().getPosition();
            var pose = event.getPoseStack();
            var buffers = game.renderBuffers().bufferSource();
            for (var span : CatenaryWireSync.visible(level)) {
                if (!event.getFrustum().isVisible(span.renderBounds)) continue;
                var origin = Vec3.atLowerCornerOf(span.origin());
                var offset = origin.subtract(camera);
                pose.pushPose();
                pose.translate(offset.x, offset.y, offset.z);
                var lightPos = BlockPos.containing(span.points.get(span.points.size() / 2).center());
                int light = LevelRenderer.getLightColor(level, lightPos);
                MESHES.computeIfAbsent(span, wire -> WireMesh.build(wire, origin))
                        .render(beams(pose, buffers, light, OverlayTexture.NO_OVERLAY));
                pose.popPose();
            }
        }
    }

    static Beams beams(PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        return new Beams(buffers, pose.last(), light, overlay);
    }

    private static final Map<HangerFrame, HangerFrameMesh> FRAME_MESHES = new WeakHashMap<>();

    static void drawFrame(Beams out, HangerFrame frame) {
        FRAME_MESHES.computeIfAbsent(frame, HangerFrameMesh::new).render(out);
    }

    private static final class Pickup implements BlockEntityRenderer<PantographBlockEntity> {
        @Override
        public void render(PantographBlockEntity pickup, float partialTick, PoseStack pose,
                           MultiBufferSource buffers, int light, int overlay) {
            var level = pickup.getLevel();
            if (level == null) return;
            var pos = Vec3.atLowerCornerOf(pickup.getBlockPos());
            var foundation = pickup.base().subtract(pos);
            var facing = pickup.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
            boolean raised = pickup.getBlockState().getValue(CatenaryBlock.RAISED);
            var across = Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(CatenarySpan.HALF_SPACING);
            var center = foundation.add(0, raised ? 1 : 0.22, 0);
            var body = SableCompanion.INSTANCE.getContainingClient(pickup);
            if (body != null && raised) {
                var bodyPose = body.renderPose(partialTick);
                var world = bodyPose.transformPosition(pickup.base());
                var contact = CatenaryWireSync.nearest(level, world);
                if (contact != null) {
                    var left = inverse(bodyPose, contact.head().supply()).subtract(pos);
                    var right = inverse(bodyPose, contact.head().returnWire()).subtract(pos);
                    center = left.lerp(right, 0.5);
                    across = left.subtract(right).scale(0.5);
                }
            }
            var out = beams(pose, buffers, light, overlay);
            var head = center.add(0, -0.065, 0);
            var elbow = foundation.lerp(head, 0.48).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(raised ? 0.37 : 0.23));
            for (double side : new double[]{-0.42, 0.42}) {
                var offset = across.scale(side);
                out.beam(foundation.add(offset), elbow.add(offset), 0.057, METAL);
                out.beam(elbow.add(offset), head.add(offset), 0.047, STEEL);
            }
            out.beam(head.subtract(across.scale(1.24)), head.add(across.scale(1.24)), 0.051, CERAMIC);
            for (double side : new double[]{-1, 1}) {
                var posOnHead = center.add(across.scale(side)).add(0, -0.035, 0);
                var direction = across.cross(new Vec3(0, 1, 0)).normalize().scale(0.13);
                out.beam(posOnHead.subtract(direction), posOnHead.add(direction), 0.038, CARBON);
            }
        }

        @Override
        public AABB getRenderBoundingBox(PantographBlockEntity pickup) {
            return new AABB(pickup.getBlockPos()).inflate(2, 6, 2);
        }
    }

    private static Vec3 inverse(dev.ryanhcode.sable.companion.math.Pose3dc pose, Vec3 point) {
        var result = pose.transformPositionInverse(new Vector3d(point.x, point.y, point.z));
        return new Vec3(result.x, result.y, result.z);
    }

    @FunctionalInterface
    private interface Face {
        void add(Vec3 normal, Vec3 first, Vec3 second, Vec3 third, Vec3 fourth);
    }

    private static void beamFaces(Vec3 first, Vec3 second, double width, Face output) {
        var axis = second.subtract(first).normalize();
        if (axis.lengthSqr() < 1e-8) return;
        var lateral = axis.cross(Math.abs(axis.y) > 0.85 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        var up = axis.cross(lateral);
        var a = lateral.add(up).scale(width);
        var b = lateral.subtract(up).scale(width);
        var corners = new Vec3[]{a, b, a.scale(-1), b.scale(-1)};
        for (int i = 0; i < 4; i++) {
            var p = corners[i];
            var q = corners[(i + 1) % 4];
            output.add(p.add(q).normalize(), first.add(p), first.add(q), second.add(q), second.add(p));
        }
    }

    private static final class WireMesh {
        private final float[] data;
        private final int[] colors;
        private int used;

        private WireMesh(int faces) {
            data = new float[faces * 15];
            colors = new int[faces];
        }

        static WireMesh build(CatenarySpan span, Vec3 origin) {
            var mesh = new WireMesh(8 * (span.points.size() - 1 + 3 * ((span.crossoverAtStart ? 1 : 0) + (span.crossoverAtEnd ? 1 : 0))));
            for (int i = 1; i < span.points.size(); i++) {
                var a = span.points.get(i - 1);
                var b = span.points.get(i);
                var first = i == 1 && span.crossoverAtStart ? mesh.crossover(a, b, origin) : a;
                var last = i == span.points.size() - 1 && span.crossoverAtEnd ? mesh.crossover(b, a, origin) : b;
                mesh.wire(first.supply().subtract(origin), last.supply().subtract(origin), COPPER);
                mesh.wire(first.returnWire().subtract(origin), last.returnWire().subtract(origin), COPPER);
            }
            return mesh;
        }

        private CatenarySpan.Head crossover(CatenarySpan.Head joint, CatenarySpan.Head neighbour, Vec3 origin) {
            var travel = neighbour.center().subtract(joint.center());
            var near = joint.center().add(travel.normalize().scale(Math.min(0.125, travel.length() / 3)));
            var trim = new CatenarySpan.Head(near, joint.across());
            bridge(trim.supply().subtract(origin), joint.returnWire().subtract(origin), 0.22);
            bridge(trim.returnWire().subtract(origin), joint.supply().subtract(origin), 0.34);
            return trim;
        }

        private void bridge(Vec3 from, Vec3 to, double clearance) {
            var highFrom = from.add(0, clearance, 0);
            var highTo = to.add(0, clearance, 0);
            wire(from, highFrom, INSULATED_COPPER);
            wire(highFrom, highTo, INSULATED_COPPER);
            wire(highTo, to, INSULATED_COPPER);
        }

        private void wire(Vec3 from, Vec3 to, int color) {
            beamFaces(from, to, 0.03125, (normal, a, b, c, d) -> face(normal, a, b, c, d, color));
        }

        private void face(Vec3 normal, Vec3 first, Vec3 second, Vec3 third, Vec3 fourth, int color) {
            colors[used / 15] = color;
            vector(normal);
            vector(first);
            vector(second);
            vector(third);
            vector(fourth);
        }

        private void vector(Vec3 value) {
            data[used++] = (float) value.x;
            data[used++] = (float) value.y;
            data[used++] = (float) value.z;
        }

        void render(Beams out) {
            out.begin(SOLID);
            for (int offset = 0; offset < used; offset += 15) {
                out.normal(data[offset], data[offset + 1], data[offset + 2]);
                for (int i = offset + 3; i < offset + 15; i += 3) {
                    out.vertex(data[i], data[i + 1], data[i + 2], colors[offset / 15]);
                }
            }
        }
    }

    static final class Beams {
        private final MultiBufferSource buffers;
        private VertexConsumer buffer;
        private final PoseStack.Pose pose;
        private final int light;
        private final int overlay;
        private final Vector3f position = new Vector3f();
        private final Vector3f faceNormal = new Vector3f();

        Beams(MultiBufferSource buffers, PoseStack.Pose pose, int light, int overlay) {
            this.buffers = buffers;
            this.pose = pose;
            this.light = light;
            this.overlay = overlay;
        }

        void begin(RenderType type) {
            buffer = buffers.getBuffer(type);
        }

        void beam(Vec3 first, Vec3 second, double width, int color) {
            begin(SOLID);
            beamFaces(first, second, width, (normal, a, b, c, d) -> {
                normal((float) normal.x, (float) normal.y, (float) normal.z);
                vertex(a, color);
                vertex(b, color);
                vertex(c, color);
                vertex(d, color);
            });
        }

        void normal(float x, float y, float z) {
            pose.transformNormal(x, y, z, faceNormal);
        }

        private void vertex(Vec3 value, int color) {
            vertex((float) value.x, (float) value.y, (float) value.z, color);
        }

        private void vertex(float x, float y, float z, int color) {
            vertex(x, y, z, color, 0.5f, 0.5f);
        }

        void vertex(float x, float y, float z, int color, float u, float v) {
            pose.pose().transformPosition(x, y, z, position);
            buffer.addVertex(position.x, position.y, position.z, color, u, v, overlay, light,
                    faceNormal.x, faceNormal.y, faceNormal.z);
        }
    }
}
