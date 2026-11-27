package org.cha0scollective.powerdock;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All positions are relative to the stationary hanger block. */
final class HangerFrame {
    static final int STEEL = 0xff68716c, EDGE = 0xff49534e, PLATE = 0xff535e59, BOLT = 0xffa0a49a;
    static final int CERAMIC = 0xffccc5ac, COPPER = 0xffaa6748;
    static final Vec3 UP = new Vec3(0, 1, 0);
    private static final double PX = 1.0 / 16;
    final List<Member> members;
    private final Map<String, Member> beamEnvelopes = new HashMap<>();
    final Vec3 housing;
    final Vec3 across;
    final Vec3 depth;
    final Vec3 feedPlate;
    final Vec3 postSpacing;
    final ClippedRoute[] leads = new ClippedRoute[2];

    record Member(String id, Vec3 start, Vec3 axis, Vec3 u, Vec3 v, double length,
                  double halfU, double halfV, int color, boolean tube) {
        Vec3 point(double x, double y, double t) {
            return start.add(u.scale(x * halfU)).add(v.scale(y * halfV)).add(axis.scale(t * length));
        }
        Vec3 local(Vec3 point) {
            var delta = point.subtract(start);
            return new Vec3(delta.dot(u) / halfU, delta.dot(v) / halfV, delta.dot(axis) / length);
        }
        Vec3 hit(Vec3 eye, Vec3 end) {
            return new AABB(-1, -1, 0, 1, 1, 1).clip(local(eye), local(end)).orElse(null);
        }
    }

    HangerFrame(HangerFixture fixture, FrameStyle style, Vec3 across) {
        this(fixture, style, across, List.of());
    }

    HangerFrame(HangerFixture fixture, FrameStyle style, Vec3 across, Collection<TubeFitting> existingFittings) {
        this(fixture, style, across, existingFittings, 0, null);
    }

    HangerFrame(HangerFixture fixture, FrameStyle style, Vec3 across, Collection<TubeFitting> existingFittings,
                int leadMask, Vec3[] headEnds) {
        housing = fixture.housing();
        this.across = across.normalize();
        depth = new Vec3(-this.across.z, 0, this.across.x);
        var parts = new ArrayList<Member>();
        var center = fixture.crossbeamCenter();
        double half = fixture.width() * 0.5 + 0.1875;
        double tip = Math.max(0.625, fixture.contactSpacing().length() + 0.25);
        var left = center.subtract(this.across.scale(half));
        var right = center.add(this.across.scale(half));
        int side = style.side();
        double reach = style.reach(side);
        var shoulder = side < 0 ? left : right;
        var core = fixture.insulatorTop(center, -1);
        var other = fixture.insulatorTop(center, 1);
        boolean oldHead = fitted(existingFittings, "head");
        double spreaderRadius = oldHead ? 2 * PX : PX;
        var headOverhang = fixture.contactSpacing().normalize().scale(oldHead ? 3 * PX : PX);
        add(parts, oldHead ? "head" : "spreader", core.subtract(headOverhang),
                other.add(headOverhang), spreaderRadius, spreaderRadius, STEEL, true);
        var joint = fixture.housingJoint(center);
        var upper = new Vec3(joint.x, center.y, joint.z);
        add(parts, "housing-neck", joint, upper, PX, PX, STEEL, false);
        var headJoint = fixture.beamJoint(center);
        add(parts, "housing-clamp", upper, headJoint, PX, PX, STEEL, false);
        collar(parts, "box-saddle", joint, 1.5, .5);
        for (int sign : new int[]{-1, 1}) {
            var low = fixture.insulatorBottom(sign);
            var insulatorTop = fixture.insulatorTop(center, sign);
            add(parts, "insulator", low, insulatorTop, .065, .065, CERAMIC, false);
            collar(parts, "insulator-seat", insulatorTop, 1.25, .5);
            for (int ring = 1; ring <= 3; ring++) {
                var base = low.add(0, ring * .125, 0);
                add(parts, "ceramic-ring", base, base.add(0, .0625, 0), .125, .125, CERAMIC, false);
            }
        }
        Vec3 feed = null;
        switch (style.kind()) {
            case PORTAL, CANTILEVER -> {
                boolean portal = style.kind() == FrameStyle.Kind.PORTAL;
                var end = center.subtract(this.across.scale(tip * side)).add(0, .25, 0);
                var top = shoulder.add(0, .25, 0);
                if (portal) {
                    add(parts, "arm", left.add(0, .25, 0), right.add(0, .25, 0), .125, .125, STEEL, true);
                } else {
                    bracketArm(parts, top, end, this.across.scale(-side), existingFittings);
                }
                for (int sign : new int[]{-1, 1}) {
                    if (style.kind() == FrameStyle.Kind.CANTILEVER && sign != side) continue;
                    var root = center.add(this.across.scale(half * sign));
                    var foot = new Vec3(root.x, fixture.railY() - style.reach(sign), root.z);
                    var post = root.add(0, .4375, 0);
                    var inward = this.across.scale(-sign);
                    var beamSeat = root.add(0, .25, 0);
                    String suffix = sign < 0 ? "-left" : "-right";
                    add(parts, "leg" + suffix, foot, post, .1875, .1875, STEEL, true);
                    anchorPlate(parts, foot, 1);
                    if (!portal) {
                        var oldTop = root.add(0, 1.375, 0);
                        boolean oldBrace = fitted(existingFittings, "brace" + suffix);
                        var upperPin = beamSeat.add(0, Math.max(1.125, half * .33), 0);
                        var mastPin = oldBrace ? oldTop : upperPin;
                        var extensionEnd = upperPin.add(0, 2 * PX, 0);
                        if (fitted(existingFittings, "cap" + suffix)) {
                            add(parts, "cap" + suffix, post, oldTop, .15625, .15625, STEEL, true);
                            add(parts, "mast-tip", oldTop, extensionEnd, .15625, .15625, STEEL, false);
                        } else {
                            add(parts, "upper-mast" + suffix, post, extensionEnd, .1875, .1875, STEEL, true);
                        }
                        mastHinge(parts, beamSeat, inward);
                        mastHinge(parts, mastPin, inward);
                        if (oldBrace) {
                            var toe = center.add(this.across.scale(sign * half * .32)).add(0, .375, 0);
                            legacyStraps(parts, "brace" + suffix, oldTop, toe);
                            rodClamp(parts, toe.add(0, -2 * PX, 0), 2, 1.5);
                        } else {
                            var heel = mastPin.add(inward.scale(5 * PX));
                            var clamp = center.add(inward.scale(3 * PX)).add(0, .25, 0);
                            var pin = clamp.add(0, 2 * PX, 0);
                            add(parts, "tie" + suffix, heel, pin, .5 * PX, .5 * PX, STEEL, true);
                            rodClamp(parts, clamp, 2, 1.5);
                        }
                    } else {
                        endPlate(parts, beamSeat.add(inward.scale(3 * PX)), inward);
                        var knee = root.add(0, -.625, 0);
                        var toe = root.add(inward.scale(.75)).add(0, .16, 0);
                        kneeBrace(parts, "brace" + suffix, knee, toe, inward, beamSeat.y, 3, 3, existingFittings);
                    }
                    if (sign == side) feed = foot.add(0, .6875, 0).add(depth.scale(.1875));
                }
            }
            case WALL -> {
                var wall = shoulder.add(this.across.scale(side * Math.max(-half + tip, reach)));
                var inward = this.across.scale(-side);
                var beamSeat = wall.add(0, .25, 0);
                bracketArm(parts, beamSeat, center.subtract(this.across.scale(tip * side)).add(0, .25, 0),
                        inward, existingFittings);
                add(parts,"wall-plate", wall.add(0, -.625, 0), wall.add(0, .5, 0), .3125, .125, PLATE, false);
                for (int sideOfPlate : new int[]{-1, 1}) {
                    var row = wall.add(depth.scale(sideOfPlate * 3.5 * PX)).add(inward.scale(2 * PX));
                    anchorBolt(parts, row.add(0, -8 * PX, 0), inward);
                    anchorBolt(parts, row.add(0, 6 * PX, 0), inward);
                }
                hinge(parts, beamSeat, inward, 2 * PX);
                var heel = wall.add(0, -.5, 0);
                var toe = center.lerp(wall, .4).add(0, .1875, 0);
                kneeBrace(parts, "brace", heel, toe, inward, beamSeat.y, 2, 1.5, existingFittings);
                feed = wall.add(depth.scale(.3125));
            }
            case CEILING -> {
                var top = center.add(0, 1 + Math.max(0, style.rightReach()), 0);
                boolean oldDrop = fitted(existingFittings, "drop");
                double radius = oldDrop ? 2 * PX : PX;
                add(parts, oldDrop ? "drop" : "drop-tube", center, top, radius, radius, STEEL, true);
                anchorPlate(parts, top, -1);
                collar(parts, "ceiling-shoe", top.add(0, -PX, 0), radius / PX, 1);
                collar(parts, "drop-seat", center, 1.25, .75);
                feed = top.add(0, -.3, 0).add(depth.scale(.125));
            }
            case FLOATING -> {}
        }
        if (style.kind() == FrameStyle.Kind.PORTAL || style.kind() == FrameStyle.Kind.CANTILEVER
                || style.kind() == FrameStyle.Kind.WALL) {
            add(parts, "head-drop", center, center.add(0, .25, 0), PX, PX, STEEL, false);
            collar(parts, "head-clamp", center.add(0, .25, 0), style.kind() == FrameStyle.Kind.PORTAL ? 3 : 1, .75);
        }
        postSpacing = this.across.scale(.125);
        feedPlate = style.terminals() ? feed : null;
        if (feedPlate != null) {
            add(parts, "terminal-plate", feedPlate.add(0, -.1875, 0), feedPlate.add(0, .1875, 0), .09375, .3125, PLATE, false);
            for (int sign : new int[]{-1, 1}) {
                var screw = feedPlate.add(this.across.scale(sign * 3.5 * PX)).add(0, 2 * PX, 0).add(depth.scale(1.5 * PX));
                add(parts, "terminal-screw", screw, screw.add(depth.scale(PX)), PX / 2, PX / 2, BOLT, false);
            }
            for (int i = 0;i<2;i++) {
                var p = post(i);
                add(parts,"feed-post-" + i, p.subtract(depth.scale(.1875)), p, .0625, .0625, COPPER, false);
            }
        }
        for (var fitting : existingFittings) {
            var envelope = beamEnvelopes.get(fitting.tube());
            if (envelope == null) continue;
            // Seat pre-existing connectors at the same depth as the former square section.
            double distance = fitting.at().z * envelope.length;
            double from = Math.max(0, distance - 3 / 16.0);
            double to = Math.min(envelope.length, distance + 3 / 16.0);
            if (to > from) parts.add(new Member("mounting-packer", envelope.start.add(envelope.axis.scale(from)),
                    envelope.axis, envelope.u, envelope.v, to - from,
                    envelope.halfU - .001, envelope.halfV - .001, PLATE, false));
        }
        if (leadMask != 0 && feedPlate != null && ClippedRoute.supported(style)) {
            double armDepth = (style.kind() == FrameStyle.Kind.PORTAL || fitted(existingFittings, "arm") ? 3 : 1) * PX;
            for (int pin = 0; pin < 2; pin++) if ((leadMask & (1 << pin)) != 0) {
                leads[pin] = ClippedRoute.lay(this, style, fixture, pin, headEnds[pin], armDepth);
                parts.addAll(leads[pin].parts());
            }
        }
        members = List.copyOf(parts);
    }
    Vec3 post(int i) { return feedPlate.add(postSpacing.scale(i == 0 ? -1 : 1)).add(depth.scale(.1875)); }
    Member member(String id) {
        for (var member : members) if (member.id.equals(id)) return member;
        return beamEnvelopes.get(id);
    }

    private void anchorPlate(List<Member> parts, Vec3 origin, int facing) {
        var outward = UP.scale(facing);
        // At the ceiling the underside carries the bolt heads.
        var face = facing == 1 ? origin.add(0, 2 * PX, 0) : origin;
        add(parts, "anchor-plate", face.subtract(outward.scale(2 * PX)), face, 5 * PX, 5 * PX, PLATE, false);
        for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            anchorBolt(parts, face.add(across.scale(x * 3.5 * PX)).add(depth.scale(z * 3.5 * PX)), outward);
        }
    }

    private void anchorBolt(List<Member> parts, Vec3 face, Vec3 normal) {
        add(parts, "anchor-bolt", face, face.add(normal.scale(.5 * PX)), .5 * PX, .5 * PX, BOLT, false);
    }

    /** A small bolted steel cuff for a square-ended member or hanger saddle. */
    private void collar(List<Member> parts, String id, Vec3 joint, double seatDepth, double halfHeight) {
        double outside = (seatDepth + .5) * PX;
        add(parts, id, joint.add(0, -halfHeight * PX, 0), joint.add(0, halfHeight * PX, 0),
                outside, (seatDepth + .5) * PX, PLATE, false);
        throughBolt(parts, joint, outside);
    }

    private static boolean fitted(Collection<TubeFitting> fittings, String member) {
        return fittings.stream().anyMatch(fitting -> fitting.tube().equals(member) || fitting.tube().startsWith(member + "/"));
    }

    private void bracketArm(List<Member> parts, Vec3 root, Vec3 tip, Vec3 inward, Collection<TubeFitting> fittings) {
        // A fitted prototype section keeps its actual flanges and existing wire endpoints.
        if (fitted(fittings, "arm")) add(parts, "arm", root, tip, 2 * PX, 2 * PX, STEEL, true);
        else add(parts, "boom", root.add(inward.scale(5 * PX)), tip, PX, PX, STEEL, true);
    }

    private void endPlate(List<Member> parts, Vec3 face, Vec3 inward) {
        var front = face.add(inward.scale(PX));
        add(parts, "boom-endplate", face, front, 3.5 * PX, 3 * PX, PLATE, false);
        for (int sign : new int[]{-1, 1}) {
            var edge = front.add(depth.scale(sign * 2.5 * PX));
            anchorBolt(parts, edge.add(0, 2 * PX, 0), inward);
            anchorBolt(parts, edge.add(0, -2 * PX, 0), inward);
        }
    }

    private void mastHinge(List<Member> parts, Vec3 center, Vec3 inward) {
        add(parts, "mast-band", center.add(0, -PX / 2, 0), center.add(0, PX / 2, 0),
                3.25 * PX, 3.25 * PX, PLATE, false);
        hinge(parts, center, inward, 3 * PX);
    }

    private void hinge(List<Member> parts, Vec3 center, Vec3 inward, double faceDepth) {
        var pin = center.add(inward.scale(5 * PX));
        for (int side : new int[]{-1, 1}) {
            var cheek = depth.scale(side * 1.5 * PX);
            add(parts, "hinge-ear", center.add(inward.scale(faceDepth)).add(cheek),
                    pin.add(inward.scale(PX)).add(cheek), .5 * PX, PX, PLATE, false);
        }
        throughBolt(parts, pin, 2 * PX);
    }

    private void rodClamp(List<Member> parts, Vec3 seat, double pinOffset, double clampDepth) {
        double bandHeight = clampDepth > 2 ? 2.25 * PX : 1.25 * PX;
        add(parts, "tube-clamp", seat.subtract(across.scale(PX)), seat.add(across.scale(PX)),
                clampDepth * PX, bandHeight, PLATE, false);
        var pin = seat.add(0, pinOffset * PX, 0);
        for (int side : new int[]{-1, 1}) {
            var cheek = depth.scale(side * PX);
            add(parts, "tie-eye", seat.add(cheek), pin.add(cheek).add(0, Math.copySign(PX, pinOffset), 0),
                    PX * .5, PX, PLATE, false);
        }
        throughBolt(parts, pin, 1.5 * PX);
    }

    private void throughBolt(List<Member> parts, Vec3 pin, double outside) {
        var tip = depth.scale(outside + .5 * PX);
        add(parts, "through-bolt", pin.subtract(tip), pin.add(tip), .5 * PX, .5 * PX, BOLT, false);
    }

    private void kneeBrace(List<Member> parts, String id, Vec3 from, Vec3 oldToe, Vec3 inward, double beamY,
                           double postDepth, double clampDepth, Collection<TubeFitting> fittings) {
        boolean legacy = fitted(fittings, id);
        var seat = new Vec3(oldToe.x, beamY, oldToe.z);
        double offset = legacy ? (oldToe.y - beamY) / PX : -(clampDepth + 1);
        var heel = from.add(inward.scale(5 * PX));
        var pin = seat.add(0, offset * PX, 0);
        if (legacy) add(parts, id, from, oldToe, 1.5 * PX, 1.5 * PX, STEEL, true);
        else add(parts, id + "-strut", heel, pin, PX, PX, STEEL, true);
        hinge(parts, from, inward, postDepth * PX);
        rodClamp(parts, seat, offset, clampDepth);
    }

    private void legacyStraps(List<Member> parts, String id, Vec3 from, Vec3 to) {
        var direction = to.subtract(from).normalize();
        beamEnvelopes.put(id, new Member(id, from, direction, depth, direction.cross(depth),
                from.distanceTo(to), 1.5 * PX, 1.5 * PX, EDGE, true));
        for (int side : new int[]{-1, 1}) {
            var offset = depth.scale(side * PX);
            add(parts, id + (side < 0 ? "/strap-a" : "/strap-b"), from.add(offset), to.add(offset),
                    PX / 2, 2 * PX, STEEL, true);
        }
    }

    void add(List<Member> parts, String id, Vec3 a, Vec3 b, double hu, double hv, int color, boolean tube) {
        var d = b.subtract(a);
        if (d.lengthSqr() < 1e-8) return;
        var axis = d.normalize();
        var u = depth.subtract(axis.scale(depth.dot(axis)));
        u = u.lengthSqr() < 1e-8 ? across : u.normalize();
        var v = axis.cross(u);
        var member = new Member(id, a, axis, u, v, d.length(), hu, hv, color, tube);
        boolean mast = id.startsWith("leg-") || id.startsWith("upper-mast");
        if (!mast && !id.equals("arm")) {
            parts.add(member);
            return;
        }

        // The two flanges and the web are real targetable faces. Keep the old envelope
        // solely for the anchors of connectors saved on a square beam.
        beamEnvelopes.put(id, member);
        double flange = 1 / 16.0;
        double halfWeb = 1 / 32.0;
        double halfDepth = mast ? hu : hv;
        var through = mast ? u : v;
        parts.add(new Member(id + "/web", a, axis, u, v, d.length(),
                mast ? hu - flange : halfWeb, mast ? halfWeb : hv - flange, EDGE, true));
        for (int side : new int[]{-1, 1}) {
            var start = a.add(through.scale(side * (halfDepth - flange / 2)));
            parts.add(new Member(id + (side < 0 ? "/flange-a" : "/flange-b"), start, axis, u, v,
                    d.length(), mast ? flange / 2 : hu + 1 / 16.0, mast ? hv : flange / 2, STEEL, true));
        }
    }
}
