package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

final class HangerMount {
    final Vec3 rail;
    final double yaw;
    final Vec3 housing;
    final VoxelShape shape;
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
            var original = CatenaryBlock.post(Direction.NORTH, i);
            posts[i] = new TerminalBoundingBox(original.getName(), bounds(original.getShape().bounds(), housing))
                    .withOrigin(transform(original.getOrigin()).add(housing).scale(16))
                    .withColor(original.getColor());
        }
        shape = Shapes.or(Shapes.create(bounds(CatenaryBlock.CASE.bounds(), housing)),
                posts[0].getShape(), posts[1].getShape());
    }

    static HangerMount fromTrack(BlockPos pos, CatenarySpan.Head rail, Direction facing) {
        var forward = new Vec3(rail.across().z, 0, -rail.across().x);
        if (forward.dot(Vec3.atLowerCornerOf(facing.getNormal())) < 0) forward = forward.scale(-1);
        return new HangerMount(rail.center().subtract(Vec3.atLowerCornerOf(pos)), Math.atan2(-forward.x, -forward.z));
    }

    TerminalBoundingBox post(int index) { return posts[index]; }

    CatenarySpan.Head wireHead(BlockPos pos, int height) {
        return new CatenarySpan.Head(Vec3.atLowerCornerOf(pos).add(rail).add(0, height, 0),
                across.scale(CatenarySpan.HALF_SPACING));
    }

    private Vec3 housingOnCrossbeam() {
        var center = new Vec3(rail.x, 0, rail.z);
        if (postsFit(center)) return center;
        // Slide along the beam when the rail center is near a block boundary. Both
        // posts must stay in the placed cell so vanilla ray picking can reach them.
        var blockCenter = new Vec3(0.5, 0, 0.5);
        var nearest = center.add(across.scale(blockCenter.subtract(center).dot(across)));
        if (!postsFit(nearest)) return blockCenter;
        var outside = center;
        var inside = nearest;
        for (int i = 0; i < 12; i++) {
            var middle = outside.lerp(inside, 0.5);
            if (postsFit(middle)) inside = middle;
            else outside = middle;
        }
        return inside;
    }

    private boolean postsFit(Vec3 center) {
        for (int i = 0; i < posts.length; i++) {
            var box = bounds(CatenaryBlock.post(Direction.NORTH, i).getOutline(), center);
            if (box.minX < 0.01 || box.maxX > 0.99 || box.minZ < 0.01 || box.maxZ > 0.99) return false;
        }
        return true;
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
