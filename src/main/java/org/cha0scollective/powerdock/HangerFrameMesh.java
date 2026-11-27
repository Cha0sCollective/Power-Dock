package org.cha0scollective.powerdock;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Pixel-mapped faces of a stationary steel fixture, baked once for each layout. */
final class HangerFrameMesh {
    private static final RenderType SURFACE = RenderType.entityCutoutNoCull(
            ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "textures/block/catenary_steelwork.png"));
    private static final int PIXELS_PER_BLOCK = 16;
    private static final int TILE_HEIGHT = 64;
    private static final int MATERIAL_WIDTH = 64;
    private static final int SHEET_WIDTH = 512;
    private static final RenderType INSULATION = RenderType.entityCutoutNoCull(
            ResourceLocation.parse("powergrid:textures/special/insulated_wire.png"));
    private final List<Quad> jackets = new ArrayList<>();
    private final List<Quad> faces = new ArrayList<>();

    // Each material has native-width strips for faces one through ten pixels wide.
    // A narrower face uses fewer texels, even on a fully extended mast.
    private enum Surface {
        FLANGE, WEB, BRACE, PLATE, FASTENER, PORCELAIN, COPPER, CUT_END, JACKET;

        static Surface of(HangerFrame.Member part) {
            if (part.id().endsWith("/web")) return WEB;
            return switch (part.color()) {
                case HangerFrame.EDGE -> BRACE;
                case HangerFrame.PLATE -> PLATE;
                case HangerFrame.BOLT -> FASTENER;
                case HangerFrame.CERAMIC -> PORCELAIN;
                case HangerFrame.COPPER -> COPPER;
                case ClippedRoute.INSULATION -> JACKET;
                default -> FLANGE;
            };
        }
    }

    HangerFrameMesh(HangerFrame frame) {
        for (var member : frame.members) addMember(member);
    }

    private void addMember(HangerFrame.Member member) {
        var surface = Surface.of(member);
        var end = surface == Surface.FLANGE || surface == Surface.WEB || surface == Surface.BRACE
                ? Surface.CUT_END : surface;
        var a = member.point(-1, -1, 0);
        var b = member.point(1, -1, 0);
        var c = member.point(1, 1, 0);
        var d = member.point(-1, 1, 0);
        var travel = member.axis().scale(member.length());
        double width = 2 * member.halfU();
        double depth = 2 * member.halfV();
        quad(end, member.axis().scale(-1), d, c, b, a, width, depth);
        quad(end, member.axis(), a.add(travel), b.add(travel), c.add(travel), d.add(travel), width, depth);
        side(surface, member.v().scale(-1), a, b, width, member);
        side(surface, member.u(), b, c, depth, member);
        side(surface, member.v(), c, d, width, member);
        side(surface, member.u().scale(-1), d, a, depth, member);
    }

    private void side(Surface surface, Vec3 normal, Vec3 left, Vec3 right, double width, HangerFrame.Member member) {
        double repeat = (double) TILE_HEIGHT / PIXELS_PER_BLOCK;
        for (double start = 0; start < member.length(); start += repeat) {
            double end = Math.min(member.length(), start + repeat);
            var low = member.axis().scale(start);
            var high = member.axis().scale(end);
            quad(surface, normal, left.add(low), right.add(low), right.add(high), left.add(high), width, end - start);
        }
    }

    private void quad(Surface surface, Vec3 normal, Vec3 a, Vec3 b, Vec3 c, Vec3 d, double width, double height) {
        if (surface == Surface.JACKET) {
            jackets.add(new Quad(normal,a,b,c,d,0,(float)width,(float)height));
            return;
        }
        int pixels = Math.clamp((int) Math.round(width * PIXELS_PER_BLOCK), 1, 10);
        int column = MATERIAL_WIDTH * surface.ordinal() + pixels * (pixels - 1) / 2;
        faces.add(new Quad(normal, a, b, c, d, (float) column / SHEET_WIDTH,
                (float) (column + pixels) / SHEET_WIDTH, (float) (height * PIXELS_PER_BLOCK / TILE_HEIGHT)));
    }

    void render(CatenaryRenderer.Beams out) {
        out.begin(SURFACE);
        draw(out,faces,-1);
        if (!jackets.isEmpty()) {
            out.begin(INSULATION);
            draw(out,jackets,0xff000000 | ClippedLeadEntity.JACKET);
        }
    }

    private static void draw(CatenaryRenderer.Beams out,List<Quad> group,int tint) {
        for (var face : group) {
            out.normal((float) face.normal.x, (float) face.normal.y, (float) face.normal.z);
            vertex(out, tint, face.a, face.left, 0);
            vertex(out, tint, face.b, face.right, 0);
            vertex(out, tint, face.c, face.right, face.height);
            vertex(out, tint, face.d, face.left, face.height);
        }
    }

    private static void vertex(CatenaryRenderer.Beams out, int tint, Vec3 p, float u, float v) {
        out.vertex((float) p.x, (float) p.y, (float) p.z, tint, u, v);
    }

    private record Quad(Vec3 normal, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float left, float right, float height) {}
}
