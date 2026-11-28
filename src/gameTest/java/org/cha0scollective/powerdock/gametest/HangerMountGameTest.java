package org.cha0scollective.powerdock.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class HangerMountGameTest {
    @GameTest(template = "empty", timeoutTicks = 50)
    public static void mountsMeetBlockGrid(GameTestHelper test) throws ReflectiveOperationException {
        var level = test.getLevel();
        var registries = level.registryAccess();
        var position = new BlockPos(2, 5, 2);
        test.setBlock(position, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger")));
        var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(test.absolutePos(position));
        var data = hanger.saveWithoutMetadata(registries);
        var mount = new CompoundTag();
        mount.putDouble("X", .5);
        mount.putDouble("Y", -3.5);
        mount.putDouble("Z", .5);
        data.put("Mount", mount);
        data.putInt("HeadOffset", 4);
        data.putInt("LeftReach", 1);
        data.putInt("RightReach", 2);
        data.putBoolean("FeedPosts", true);

        for (int turn = 0; turn < 4; turn++) {
            double yaw = turn * Math.PI / 2;
            mount.putDouble("Yaw", yaw);
            var across = new Vec3(Math.rint(Math.cos(yaw)), 0, -Math.rint(Math.sin(yaw)));
            var rail = new Vec3(.5, -4, .5);
            for (int width : new int[]{3, 4, 7, 13}) {
                int distance = (width + 2) / 2;
                data.putInt("Width", width);
                data.putString("Support", "PORTAL");
                hanger.loadWithComponents(data, registries);
                assertMountStartsAboveCrossbar(test, hanger, "head-drop");
                var feet = bounds(hanger, "anchor-plate");
                test.assertTrue(feet.size() == 2, "Expected two portal footplates");
                assertFoot(test, feet.getFirst(), rail.add(across.scale(-distance)).add(0, -1, 0));
                assertFoot(test, feet.getLast(), rail.add(across.scale(distance)).add(0, -2, 0));

                for (int side : new int[]{-1, 1}) {
                    data.putInt("SupportSide", side);
                    data.putString("Support", "CANTILEVER");
                    hanger.loadWithComponents(data, registries);
                    assertMountStartsAboveCrossbar(test, hanger, "head-drop");
                    int reach = side < 0 ? 1 : 2;
                    var outward = across.scale(side);
                    assertFoot(test, bounds(hanger, "anchor-plate").getFirst(),
                            rail.add(outward.scale(distance)).add(0, -reach, 0));

                    data.putString("Support", "WALL");
                    hanger.loadWithComponents(data, registries);
                    assertMountStartsAboveCrossbar(test, hanger, "head-drop");
                    double wall = rail.dot(outward) + distance - .5 + reach;
                    for (String plate : new String[]{"wall-plate", "terminal-plate"}) {
                        var box = bounds(hanger, plate).getFirst();
                        test.assertTrue(Math.abs(farthestFace(box, outward) - wall) < 1e-6,
                                "The " + plate + " does not sit flush with the adjacent block");
                    }
                    for (int pin = 0; pin < 2; pin++) {
                        test.assertTrue(farthestFace(bounds(hanger, "feed-post-" + pin).getFirst(), outward) < wall,
                                "A feed post enters the wall");
                    }
                }
            }
            data.putString("Support", "CEILING");
            hanger.loadWithComponents(data, registries);
            assertMountStartsAboveCrossbar(test, hanger, "drop-tube");
            var plate = bounds(hanger, "anchor-plate").getFirst();
            test.assertTrue(plate.getCenter().x == .5 && plate.getCenter().z == .5 && plate.maxY == 8,
                    "The ceiling plate should sit against the underside of a centered block");
        }

        mount.putDouble("Yaw", 0);
        data.putInt("Width", 7);
        data.putString("Support", "PORTAL");
        data.remove("LegacySupportLayout");
        hanger.loadWithComponents(data, registries);
        var legacy = bounds(hanger, "anchor-plate").getFirst();
        test.assertTrue(legacy.getCenter().x == .5 - 3.6875, "An older portal with feed posts shifted");
        hanger.loadWithComponents(hanger.saveWithoutMetadata(registries), registries);
        test.assertTrue(legacy.equals(bounds(hanger, "anchor-plate").getFirst()), "Legacy support shifted after saving");

        data.putBoolean("LegacySupportLayout", false);
        mount.putDouble("X", .625);
        hanger.loadWithComponents(data, registries);
        test.assertTrue(bounds(hanger, "anchor-plate").getFirst().getCenter().x == .625 - 3.6875,
                "An off-center rail with a cardinal tangent should keep its rail-aligned layout");
        verifyStraightPlacement(test);
        test.succeed();
    }

    private static void verifyStraightPlacement(GameTestHelper test) throws ReflectiveOperationException {
        var level = test.getLevel();
        var rail = new BlockPos(711, 80, 706);
        var chunk = new ChunkPos(rail);
        level.setChunkForced(chunk.x, chunk.z, true);
        var track = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO);
        for (int x = -5; x <= 5; x++) {
            var cell = rail.east(x);
            level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(cell, track);
        }
        TrackPropagator.onRailAdded(level, rail, track);
        var player = test.makeMockPlayer(GameType.CREATIVE);
        var target = Vec3.atBottomCenterOf(rail);
        player.setPos(target.add(0, 0, 2));
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        var item = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powerdock:catenary_hanger")));
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        var hit = new BlockHitResult(target, Direction.UP, rail, false);
        test.assertTrue(item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(),
                "Could not place the hanger on a straight Create rail");
        var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(rail.above(4));
        test.assertTrue(hanger != null, "Missing hanger over the selected track");
        var head = hanger.headPosition();
        var face = Vec3.atBottomCenterOf(head.above());
        var outline = level.getBlockState(head).getShape(level, head);
        var crossbarHit = outline.clip(face.add(0, .5, 0), face.add(0, -.25, 0), head);
        test.assertTrue(crossbarHit != null && crossbarHit.getDirection() == Direction.UP
                        && crossbarHit.getLocation().distanceToSqr(face) < 1e-8,
                "The crossbar must be selectable at the block boundary");
        var stone = new ItemStack(Blocks.STONE);
        player.setItemInHand(InteractionHand.MAIN_HAND, stone);
        test.assertTrue(stone.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, crossbarHit)).consumesAction()
                        && level.getBlockState(head.above()).is(Blocks.STONE),
                "Could not place a block directly against the original crossbar");
        test.assertTrue(bounds(hanger, "spreader").getFirst().maxY == 1, "The rendered crossbar leaves a gap");
        level.removeBlock(head.above(), false);
        var data = hanger.saveWithoutMetadata(level.registryAccess());
        data.putString("Support", "PORTAL");
        hanger.loadWithComponents(data, level.registryAccess());
        var feet = bounds(hanger, "anchor-plate");
        var south = new Vec3(.5, -4, 4.5);
        var north = new Vec3(.5, -4, -3.5);
        assertFoot(test, feet.getFirst().getCenter().z > 0 ? feet.getFirst() : feet.getLast(), south);
        assertFoot(test, feet.getFirst().getCenter().z < 0 ? feet.getFirst() : feet.getLast(), north);
        level.setChunkForced(chunk.x, chunk.z, false);
    }

    private static void assertMountStartsAboveCrossbar(GameTestHelper test, CatenaryHangerBlockEntity hanger, String part)
            throws ReflectiveOperationException {
        double top = bounds(hanger, "spreader").getFirst().maxY;
        test.assertTrue(top == hanger.headPosition().getY() - hanger.getBlockPos().getY() + 1
                        && bounds(hanger, part).getFirst().minY == top,
                "The " + part + " should start at the top of the crossbar in the block above");
    }

    private static void assertFoot(GameTestHelper test, AABB foot, Vec3 target) {
        test.assertTrue(foot.getCenter().x == target.x && foot.getCenter().z == target.z && foot.minY == target.y,
                "Footplate did not land on the center of a full block at " + target);
    }

    private static double farthestFace(AABB box, Vec3 direction) {
        return box.getCenter().dot(direction)
                + (box.getXsize() * Math.abs(direction.x) + box.getZsize() * Math.abs(direction.z)) / 2;
    }

    private static List<AABB> bounds(CatenaryHangerBlockEntity hanger, String id) throws ReflectiveOperationException {
        var getFrame = CatenaryHangerBlockEntity.class.getDeclaredMethod("frame");
        getFrame.setAccessible(true);
        var frame = getFrame.invoke(hanger);
        var members = frame.getClass().getDeclaredField("members");
        members.setAccessible(true);
        var partType = Class.forName(frame.getClass().getName() + "$Member");
        var getId = partType.getDeclaredMethod("id");
        getId.setAccessible(true);
        var getBounds = Class.forName("org.cha0scollective.powerdock.TubeFitting").getDeclaredMethod("bounds", partType);
        getBounds.setAccessible(true);
        var result = new ArrayList<AABB>();
        for (var part : (List<?>) members.get(frame)) {
            if (id.equals(getId.invoke(part))) result.add((AABB) getBounds.invoke(null, part));
        }
        return result;
    }

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
