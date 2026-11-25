package org.cha0scollective.powerdock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
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
    private static final int METAL = 0xFF485650;
    private static final int STEEL = 0xFF65736D;
    private static final int CARBON = 0xFF2C3332;
    private static final int CERAMIC = 0xFFB9B292;

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(PowerDock.CATENARY_HANGER_ENTITY.get(), unused -> new Hanger());
        event.registerBlockEntityRenderer(PowerDock.PANTOGRAPH_ENTITY.get(), unused -> new Pickup());
    }

    private static final class Hanger implements BlockEntityRenderer<CatenaryHangerBlockEntity> {
        private final Map<CatenarySpan, WireMesh> wireMeshes = new WeakHashMap<>();

        @Override
        public void render(CatenaryHangerBlockEntity hanger, float partialTick, PoseStack pose,
                           MultiBufferSource buffers, int light, int overlay) {
            var origin = Vec3.atLowerCornerOf(hanger.getBlockPos());
            var out = beams(pose, buffers, light, overlay);
            var span = hanger.span();
            if (span != null) {
                var mesh = wireMeshes.get(span);
                if (mesh == null) {
                    mesh = WireMesh.build(span, origin);
                    wireMeshes.put(span, mesh);
                }
                mesh.render(out);
            }
            var frame = hanger.fixtureHead();
            if (frame != null) drawFixture(out, frame.center().subtract(origin), frame.across(), 0,
                    hanger.width(), hanger.height());
            var mount = hanger.mount();
            if (mount != null && hanger.getBlockState().getValue(CatenaryBlock.TRACK_ALIGNED)) {
                var renderer = Minecraft.getInstance().getBlockRenderer();
                var state = PowerDock.CATENARY_HANGER.get().defaultBlockState();
                pose.pushPose();
                pose.translate(mount.housing.x, mount.housing.y, mount.housing.z);
                pose.mulPose(Axis.YP.rotation((float) mount.yaw));
                pose.translate(-0.5, 0, -0.5);
                renderer.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.solidBlockSheet()),
                        state, renderer.getBlockModel(state), 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.solid());
                pose.popPose();
            }
        }

        @Override public int getViewDistance() { return 96; }

        @Override
        public boolean shouldRenderOffScreen(CatenaryHangerBlockEntity hanger) {
            // A wire can cross the camera view while its owning hanger is outside it.
            return true;
        }

        @Override
        public AABB getRenderBoundingBox(CatenaryHangerBlockEntity hanger) {
            var span = hanger.span();
            return span != null ? span.renderBounds : new AABB(hanger.getBlockPos()).inflate(8, 16, 8);
        }
    }

    static Beams beams(PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        return new Beams(buffers.getBuffer(SOLID), pose.last(), light, overlay);
    }

    static void drawFixture(Beams out, Vec3 head, Vec3 contactSpacing, double housingY,
                            int clearanceWidth, int clearanceHeight) {
        var across = contactSpacing.normalize();
        var width = across.scale(clearanceWidth * 0.5 + 0.125);
        double beamY = housingY + 0.609375;
        // On an incline the conductors can reach the height of the top mount.
        // In that case the beam passes through the base and the insulators sit above it.
        if (Math.abs(head.y - beamY) < 0.1875) beamY = housingY + 0.125;
        var beam = new Vec3(head.x, beamY, head.z);
        out.beam(beam.subtract(width), beam.add(width), 0.125, METAL);

        double ground = head.y - clearanceHeight;
        for (int side = -1; side <= 1; side += 2) {
            var mast = beam.add(width.scale(side));
            out.beam(mast, new Vec3(mast.x, ground, mast.z), 0.125, METAL);
            var wire = head.add(contactSpacing.scale(side));
            out.beam(wire, new Vec3(wire.x, beam.y, wire.z), 0.0625, CERAMIC);
        }
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
                var contact = CatenarySpan.nearest(level, world, unused -> true);
                if (contact != null) {
                    var left = inverse(bodyPose, contact.head().supply()).subtract(pos);
                    var right = inverse(bodyPose, contact.head().returnWire()).subtract(pos);
                    center = left.lerp(right, 0.5);
                    across = left.subtract(right).scale(0.5);
                }
            }
            var out = new Beams(buffers.getBuffer(SOLID), pose.last(), light, overlay);
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
        private int used;

        private WireMesh(int segments) {
            // Two wires, four faces per segment, one normal and four vertices per face.
            data = new float[segments * 2 * 4 * 15];
        }

        static WireMesh build(CatenarySpan span, Vec3 origin) {
            var mesh = new WireMesh(span.points.size() - 1);
            for (int i = 1; i < span.points.size(); i++) {
                var a = span.points.get(i - 1);
                var b = span.points.get(i);
                beamFaces(a.supply().subtract(origin), b.supply().subtract(origin), 0.03125, mesh::face);
                beamFaces(a.returnWire().subtract(origin), b.returnWire().subtract(origin), 0.03125, mesh::face);
            }
            return mesh;
        }

        private void face(Vec3 normal, Vec3 first, Vec3 second, Vec3 third, Vec3 fourth) {
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
            for (int offset = 0; offset < used; offset += 15) {
                out.normal(data[offset], data[offset + 1], data[offset + 2]);
                for (int i = offset + 3; i < offset + 15; i += 3) {
                    out.vertex(data[i], data[i + 1], data[i + 2], COPPER);
                }
            }
        }
    }

    static final class Beams {
        private final VertexConsumer buffer;
        private final PoseStack.Pose pose;
        private final int light;
        private final int overlay;
        private final Vector3f position = new Vector3f();
        private final Vector3f faceNormal = new Vector3f();

        Beams(VertexConsumer buffer, PoseStack.Pose pose, int light, int overlay) {
            this.buffer = buffer;
            this.pose = pose;
            this.light = light;
            this.overlay = overlay;
        }

        void beam(Vec3 first, Vec3 second, double width, int color) {
            beamFaces(first, second, width, (normal, a, b, c, d) -> {
                normal((float) normal.x, (float) normal.y, (float) normal.z);
                vertex(a, color);
                vertex(b, color);
                vertex(c, color);
                vertex(d, color);
            });
        }

        private void normal(float x, float y, float z) {
            pose.transformNormal(x, y, z, faceNormal);
        }

        private void vertex(Vec3 value, int color) {
            vertex((float) value.x, (float) value.y, (float) value.z, color);
        }

        private void vertex(float x, float y, float z, int color) {
            pose.pose().transformPosition(x, y, z, position);
            buffer.addVertex(position.x, position.y, position.z, color, 0.5f, 0.5f, overlay, light,
                    faceNormal.x, faceNormal.y, faceNormal.z);
        }
    }
}
