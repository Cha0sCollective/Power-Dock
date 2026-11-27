package org.cha0scollective.powerdock.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class HangerMountGameTest {
    @GameTest(template = "empty", timeoutTicks = 50)
    public static void legacyPortalAndStableRotatedTerminals(GameTestHelper test) {
        var level = test.getLevel();
        var registries = level.registryAccess();
        var position = new BlockPos(1, 2, 1);
        test.setBlock(position, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger")));
        var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(test.absolutePos(position));
        var data = hanger.saveWithoutMetadata(registries);
        test.assertTrue("FLOATING".equals(data.getString("Support")), "A new hanger should float");
        data.remove("Support");
        data.putInt("Width", 9);
        data.putInt("Height", 8);
        var track = new CompoundTag();
        track.putDouble("X", .5);
        track.putDouble("Y", -8.25);
        track.putDouble("Z", .5);
        track.putDouble("Yaw", Math.PI / 4);
        data.put("Mount", track);
        hanger.loadWithComponents(data, registries);
        test.assertTrue("PORTAL".equals(hanger.saveWithoutMetadata(registries).getString("Support"))
                && hanger.width() == 9 && hanger.height() == 8, "Legacy hanger lost its portal or clearance");

        var positive = new BlockWireEndpoint(hanger.getBlockPos(), 0);
        var negative = new BlockWireEndpoint(hanger.getBlockPos(), 1);
        var top = positive.getExactPosition(level);
        var ret = negative.getExactPosition(level);
        for (String style : new String[]{"CANTILEVER","PORTAL","WALL","CEILING","FLOATING"}) {
            data.putString("Support", style);
            data.putBoolean("FeedPosts", true);
            data.putInt("SupportSide", -1);
            data.putInt("LeftReach", 2);
            data.putInt("RightReach", 3);
            hanger.loadWithComponents(data, registries);
            test.assertTrue(top.equals(positive.getExactPosition(level)) && ret.equals(negative.getExactPosition(level)),
                    style + " moved the existing wire endpoints");
            if (style.equals("FLOATING")) continue;
            for (int pin = 2; pin < 4; pin++) {
                var placement = hanger.terminal(hanger.getBlockState(), pin);
                test.assertTrue(placement != null && hanger.terminalIndexAt(hanger.getBlockState(), placement.getOrigin()) == pin,
                        style + " has an unselectable feed terminal " + pin);
            }
        }

        data.putString("Support", "CANTILEVER");
        data.putInt("SupportSide", 1);
        var fittings = new ListTag();
        fittings.add(fitting("leg-right", 4));
        fittings.add(fitting("brace-right", 7));
        data.put("TubeConnectors", fittings);
        hanger.loadWithComponents(data, registries);
        var one = new BlockWireEndpoint(hanger.getBlockPos(), 4);
        var two = new BlockWireEndpoint(hanger.getBlockPos(), 7);
        test.assertTrue(one.getNode(level) != null && two.getNode(level) != null && one.getNode(level) != two.getNode(level),
                "Two manual fittings shared an electrical junction");
        var oldTip = two.getExactPosition(level);
        var roundTrip = hanger.saveWithoutMetadata(registries);
        hanger.loadWithComponents(roundTrip, registries);
        var local = oldTip.subtract(Vec3.atLowerCornerOf(hanger.getBlockPos()));
        test.assertTrue(hanger.terminalIndexAt(hanger.getBlockState(), local) == 7
                        && oldTip.equals(two.getExactPosition(level)) && hanger.terminal(hanger.getBlockState(), 5) == null,
                "The rotated brace connector moved or its stable slot was renumbered");
        for (int side : new int[]{-1, 1}) {
            var former = fitting("brace-right", 7);
            former.putDouble("U", side);
            former.putInt("Face", side < 0 ? 1 : 0);
            fittings.set(1, former);
            hanger.loadWithComponents(data, registries);
            var oldSurface = two.getExactPosition(level);
            var strap = former.copy();
            strap.putString("Tube", side < 0 ? "brace-right/strap-a" : "brace-right/strap-b");
            fittings.set(1, strap);
            hanger.loadWithComponents(data, registries);
            test.assertTrue(oldSurface.distanceTo(two.getExactPosition(level)) < 1e-6,
                    "The bolted stay moved the wire anchor on side " + side);
        }
        var legacyTip = one.getExactPosition(level);
        var flange = fitting("leg-right/flange-b", 4);
        fittings.set(0, flange);
        data.put("TubeConnectors", fittings);
        hanger.loadWithComponents(data, registries);
        test.assertTrue(legacyTip.distanceTo(one.getExactPosition(level)) < 1e-6,
                "The outer flange moved the former mounting face");
        var web = fitting("leg-right/web", 4);
        web.putDouble("U", 0);
        web.putDouble("V", 1);
        web.putInt("Face", 2);
        var oldSide = web.copy();
        oldSide.putString("Tube", "leg-right");
        fittings.set(0, oldSide);
        hanger.loadWithComponents(data, registries);
        var sideTip = one.getExactPosition(level);
        fittings.set(0, web);
        hanger.loadWithComponents(data, registries);
        test.assertTrue(Math.abs(sideTip.distanceTo(one.getExactPosition(level)) - 2.5 / 16) < 1e-6,
                "A connector on the recessed web used the beam envelope instead");
        for (int side : new int[]{-1, 1}) {
            data.putInt("SupportSide", side);
            for (int width : new int[]{3, 7, 13}) {
                data.putInt("Width", width);
                fittings.set(0, fitting("boom", 4));
                fittings.set(1, fitting(side < 0 ? "tie-left" : "tie-right", 7));
                hanger.loadWithComponents(data, registries);
                test.assertTrue(hanger.terminal(hanger.getBlockState(), 4) != null
                                && hanger.terminal(hanger.getBlockState(), 7) != null,
                        "Lost the boom or tie fitting at width " + width);
                var boomTip = one.getExactPosition(level);
                var tieTip = two.getExactPosition(level);
                test.assertTrue(tieTip.y > boomTip.y, "The tie crossed into the space below the working arm");
                hanger.loadWithComponents(hanger.saveWithoutMetadata(registries), registries);
                test.assertTrue(boomTip.equals(one.getExactPosition(level)) && tieTip.equals(two.getExactPosition(level)),
                        "The arm fittings moved on reload");
            }
        }
        test.succeed();
    }

    private static CompoundTag fitting(String tube, int slot) {
        var tag = new CompoundTag();
        tag.putString("Tube", tube);
        tag.putInt("Slot", slot);
        tag.putInt("Face", 0);
        tag.putDouble("U", 1);
        tag.putDouble("Along", .5);
        return tag;
    }
}
