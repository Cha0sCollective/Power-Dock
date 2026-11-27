package org.cha0scollective.powerdock;

import net.minecraft.world.phys.Vec3;

public record HangerFixture(Vec3 wireCenter, Vec3 contactSpacing, Vec3 housing, double railY, int width) {
    static final double BEAM_RISE = 10.0 / 16;

    public Vec3 crossbeamCenter() {
        return wireCenter.add(0, BEAM_RISE, 0);
    }

    Vec3 mastOffset() {
        return contactSpacing.normalize().scale(width * 0.5 + 0.125);
    }

    public Vec3 insulatorBottom(int side) {
        return wireCenter.add(contactSpacing.scale(side));
    }

    public Vec3 insulatorTop(Vec3 beam, int side) {
        return beam.add(contactSpacing.scale(side));
    }

    Vec3 housingJoint(Vec3 beam) {
        // Seat the mounting shoe on the five-pixel-high enclosure.
        return housing.add(0, Math.clamp(beam.y - housing.y, 0, 5.0 / 16), 0);
    }

    Vec3 beamJoint(Vec3 beam) {
        var across = contactSpacing.normalize();
        return beam.add(across.scale(housing.subtract(beam).dot(across)));
    }
}
