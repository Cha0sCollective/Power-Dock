package org.cha0scollective.powerdock;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** The insulation, retaining clips and electrical length all follow the same path in the hanger frame. */
record ClippedRoute(List<HangerFrame.Member> parts, double length) {
    private static final double PX = 1.0 / 16;
    static final int INSULATION = 0xff27272b;

    static boolean supported(FrameStyle style) {
        return style.kind() == FrameStyle.Kind.CANTILEVER || style.kind() == FrameStyle.Kind.PORTAL;
    }

    static int pin(HangerFrame.Member part) {
        return switch (part.id()) {
            case "lead-positive", "clip-positive" -> 0;
            case "lead-negative", "clip-negative" -> 1;
            default -> -1;
        };
    }

    static ClippedRoute lay(HangerFrame frame, FrameStyle style, HangerFixture fixture, int pin, Vec3 headEnd,
                            double armDepth) {
        var parts = new ArrayList<HangerFrame.Member>();
        String wire = pin == 0 ? "lead-positive" : "lead-negative";
        String clip = pin == 0 ? "clip-positive" : "clip-negative";
        var across = frame.across;
        var depth = frame.depth;
        var inward = across.scale(-style.side());
        var start = frame.post(pin);
        var beam = fixture.crossbeamCenter().add(0, .25, 0);
        // Keep the two lanes in order through the elbow, on either mounting side.
        double lane = (pin == 0 ? -1 : 1) * PX * style.side();
        var riser = start.subtract(depth.scale(2.1 * PX)).add(0, 2 * PX, 0);
        var elbow = new Vec3(riser.x, beam.y + lane, riser.z);
        var bend = elbow.add(inward.scale(4 * PX));
        var armStart = bend.add(inward.scale(3 * PX)).add(depth.scale(armDepth - 3 * PX));
        var headApproach = armStart.add(inward.scale(headEnd.subtract(armStart).dot(inward)));
        var overPost = headApproach.add(depth.scale(headEnd.subtract(headApproach).dot(depth)));
        var points = List.of(start, start.add(0, PX, 0), riser, elbow.add(0,-3 * PX,0), bend,
                armStart, headApproach, overPost, headEnd);
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            length += a.distanceTo(b);
            frame.add(parts, wire, a, b, .62 * PX, .62 * PX, INSULATION, false);
        }
        clips(frame, parts, clip, riser, points.get(3), across, depth);
        clips(frame, parts, clip, armStart, headApproach, HangerFrame.UP, depth);
        return new ClippedRoute(List.copyOf(parts), length);
    }

    private static void clips(HangerFrame frame, List<HangerFrame.Member> parts, String id,
                              Vec3 a, Vec3 b, Vec3 transverse, Vec3 outward) {
        int intervals = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 1.5));
        for (int i = 0; i <= intervals; i++) {
            var p = a.lerp(b, (i + .25) / (intervals + .5));
            var crown = p.add(outward.scale(.7 * PX));
            frame.add(parts, id, crown.subtract(transverse.scale(.9 * PX)), crown.add(transverse.scale(.9 * PX)),
                    .35 * PX, .35 * PX, HangerFrame.BOLT, false);
            for (int side : new int[]{-1, 1}) {
                var end = crown.add(transverse.scale(side * .9 * PX));
                frame.add(parts, id, end.subtract(outward.scale(1.9 * PX)), end, .3 * PX, .35 * PX, HangerFrame.PLATE, false);
            }
        }
    }
}
