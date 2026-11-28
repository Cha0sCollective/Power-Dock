package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

import javax.annotation.Nullable;

final class HangerMount {
    private static final double TERMINAL_INSET = 1.0 / 128;
    private static final double GRID_DIRECTION_TOLERANCE = 1e-5;
    private static final double GRID_POSITION_TOLERANCE = 1.0 / 1024;
    final Vec3 rail;
    final double yaw;
    final Vec3 housing;
    final VoxelShape shape;
    final VoxelShape outline;
    private final TerminalBoundingBox[] posts = new TerminalBoundingBox[2];
    private final Vec3 across;
    private final Vec3 back;

    HangerMount(Vec3 rail, double yaw) {
        this.rail = rail;
        this.yaw = yaw;
        across = new Vec3(Math.cos(yaw), 0, -Math.sin(yaw));
        back = new Vec3(Math.sin(yaw), 0, Math.cos(yaw));
        housing = housingOnCrossbeam();
        for (int i = 0; i < posts.length; i++) {
            var original = CatenaryBlock.hangerPost(Direction.NORTH, i);
            posts[i] = new TerminalBoundingBox(original.getName(), bounds(original.getShape().bounds(), housing))
                    .withOrigin(transform(original.getOrigin()).add(housing).scale(16))
                    .withColor(original.getColor());
        }
        shape = Shapes.or(Shapes.create(bounds(CatenaryBlock.HANGER_CASE.bounds(), housing)),
                posts[0].getShape(), posts[1].getShape());
        outline = Shapes.or(shape, crossbarOutline());
    }

    static HangerMount fromTrack(BlockPos pos, CatenarySpan.Head rail, Direction facing) {
        var forward = new Vec3(rail.across().z, 0, -rail.across().x);
        if (forward.dot(Vec3.atLowerCornerOf(facing.getNormal())) < 0) forward = forward.scale(-1);
        return new HangerMount(rail.center().subtract(Vec3.atLowerCornerOf(pos)), Math.atan2(-forward.x, -forward.z));
    }

    TerminalBoundingBox post(int index) { return posts[index]; }

    private VoxelShape crossbarOutline() {
        var result = Shapes.empty();
        var center = new Vec3(rail.x, 0, rail.z);
        double half = HangerFixture.CROSSBAR_HALF_HEIGHT;
        // Use short sections so the outline follows the bar on a curve.
        for (int pixel = -9; pixel < 9; pixel++) {
            var box = bounds(new AABB(.5 + pixel / 16.0, 1 - 2 * half, .5 - half,
                    .5 + (pixel + 1) / 16.0, 1, .5 + half), center);
            double minX = Math.max(0, box.minX), maxX = Math.min(1, box.maxX);
            double minZ = Math.max(0, box.minZ), maxZ = Math.min(1, box.maxZ);
            if (minX < maxX && minZ < maxZ) {
                result = Shapes.or(result, Shapes.box(minX, box.minY, minZ, maxX, box.maxY, maxZ));
            }
        }
        return result;
    }

    @Nullable Vec3 gridRail() {
        if (Math.min(Math.abs(across.x), Math.abs(across.z)) > GRID_DIRECTION_TOLERANCE) return null;
        double x = Math.floor(rail.x) + .5;
        double z = Math.floor(rail.z) + .5;
        return Math.abs(rail.x - x) <= GRID_POSITION_TOLERANCE && Math.abs(rail.z - z) <= GRID_POSITION_TOLERANCE
                ? new Vec3(x, rail.y, z) : null;
    }

    CatenarySpan.Head wireHead(BlockPos pos, int height) {
        return new CatenarySpan.Head(Vec3.atLowerCornerOf(pos).add(rail).add(0, height, 0),
                across.scale(CatenarySpan.HALF_SPACING)).alignToHanger();
    }

    private Vec3 housingOnCrossbeam() {
        var center = new Vec3(rail.x, 0, rail.z);
        var positive = bounds(CatenaryBlock.hangerPost(Direction.NORTH, 0).getShape().bounds(), Vec3.ZERO);
        var negative = bounds(CatenaryBlock.hangerPost(Direction.NORTH, 1).getShape().bounds(), Vec3.ZERO);
        var posts = positive.minmax(negative);
        var fitX = new Range(TERMINAL_INSET - posts.minX, 1 - TERMINAL_INSET - posts.maxX);
        var fitZ = new Range(TERMINAL_INSET - posts.minZ, 1 - TERMINAL_INSET - posts.maxZ);

        // Keep both posts in the selectable cell while moving the box the least distance along the crossbeam.
        var slideX = fitX.slideFrom(center.x, across.x);
        var slideZ = fitZ.slideFrom(center.z, across.z);
        double low = Math.max(slideX.min, slideZ.min);
        double high = Math.min(slideX.max, slideZ.max);
        if (low <= high) {
            return center.add(across.scale(Math.clamp(0, low, high)));
        }

        // At the edge of a cell, the beam can miss the entire area where both terminals fit.
        return new Vec3(fitX.clamp(center.x), 0, fitZ.clamp(center.z));
    }

    private record Range(double min, double max) {
        Range slideFrom(double position, double direction) {
            if (Math.abs(direction) < 1e-8) {
                return position >= min && position <= max
                        ? new Range(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                        : new Range(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            }
            double first = (min - position) / direction;
            double second = (max - position) / direction;
            return new Range(Math.min(first, second), Math.max(first, second));
        }

        double clamp(double position) { return Math.clamp(position, min, max); }
    }

    private AABB bounds(AABB box, Vec3 center) {
        var a = transform(new Vec3(box.minX, box.minY, box.minZ)).add(center);
        var b = transform(new Vec3(box.maxX, box.maxY, box.maxZ)).add(center);
        var c = transform(new Vec3(box.minX, box.minY, box.maxZ)).add(center);
        var d = transform(new Vec3(box.maxX, box.maxY, box.minZ)).add(center);
        return new AABB(a, b).minmax(new AABB(c, d));
    }

    private Vec3 transform(Vec3 model) {
        return across.scale(model.x - 0.5).add(back.scale(model.z - 0.5)).add(0, model.y, 0);
    }
}
