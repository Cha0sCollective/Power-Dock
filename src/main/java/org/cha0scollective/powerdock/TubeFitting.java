package org.cha0scollective.powerdock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/** A PG connector stays on the same face and fraction of a tube as the frame changes size. */
record TubeFitting(String tube, Vec3 at, int face) {
    static TubeFitting on(HangerFrame.Member tube, Vec3 point) {
        double[] distances = {Math.abs(point.x-1), Math.abs(point.x+1), Math.abs(point.y-1),
                Math.abs(point.y+1), Math.abs(point.z-1), Math.abs(point.z)};
        int closest = 0;
        for (int i = 1;i<distances.length;i++) if (distances[i]<distances[closest]) closest = i;
        return new TubeFitting(tube.id(), point, closest);
    }

    HangerFrame.Member body(HangerFrame frame) {
        var member = frame.member(tube);
        if (member == null || !member.tube()) return null;
        var normal = switch (face/2) { case 0 -> member.u(); case 1 -> member.v(); default -> member.axis(); };
        if ((face & 1) != 0) normal = normal.scale(-1);
        var u = face/2 == 0 ? member.v() : member.u();
        return new HangerFrame.Member("connector", member.point(at.x, at.y, at.z), normal, u, normal.cross(u),
                10/16d, 3/16d, 3/16d, HangerFrame.CERAMIC, false);
    }

    static TerminalBoundingBox terminal(HangerFrame.Member body, int index) {
        if (body == null) return null;
        var bounds = bounds(body);
        var name = index == 2 ? IDecoratedTerminal.POSITIVE : index == 3 ? IDecoratedTerminal.NEGATIVE : IDecoratedTerminal.CONNECTOR;
        var terminal = new TerminalBoundingBox(name, bounds).withOrigin(body.point(0, 0, index<4 ? 1 : .8).scale(16));
        return index == 2 ? terminal.withColor(IDecoratedTerminal.RED) : index == 3 ? terminal.withColor(IDecoratedTerminal.BLUE) : terminal;
    }

    static AABB bounds(HangerFrame.Member body) {
        var bounds = new AABB(body.start(), body.start());
        for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z = 0;z<=1;z++)
            bounds = bounds.minmax(new AABB(body.point(x, y, z), body.point(x, y, z)));
        return bounds;
    }

    CompoundTag write(int index) {
        var tag = new CompoundTag();
        tag.putInt("Slot", index);
        tag.putString("Tube", tube);
        tag.putDouble("U", at.x);
        tag.putDouble("V", at.y);
        tag.putDouble("Along", at.z);
        tag.putInt("Face", face);
        return tag;
    }
    static TubeFitting read(CompoundTag tag) {
        var at = new Vec3(tag.getDouble("U"), tag.getDouble("V"), tag.getDouble("Along"));
        int face = tag.getInt("Face");
        return Math.abs(at.x)<=1.000001 && Math.abs(at.y)<=1.000001 && at.z>=-1e-6 && at.z<=1.000001
                && face>=0 && face<6 ? new TubeFitting(tag.getString("Tube"), at, face) : null;
    }
}
