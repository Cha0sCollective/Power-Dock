package org.cha0scollective.powerdock.gametest;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackShape;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;

import java.util.OptionalInt;
import java.util.UUID;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class CurvedHangerSettingsGameTest {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void curvedSettingsUseTheMountedTrack(GameTestHelper test) {
        var level = test.getLevel();
        var start = new BlockPos(602, 80, 602);
        var end = start.offset(12, 0, 7);
        var startChunk = new ChunkPos(start);
        var endChunk = new ChunkPos(end);
        level.setChunkForced(startChunk.x, startChunk.z, true);
        level.setChunkForced(endChunk.x, endChunk.z, true);
        var track = (ITrackBlock) AllBlocks.TRACK.get();
        var startState = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.HAS_BE, true)
                .setValue(TrackBlock.SHAPE, TrackShape.XO);
        var endState = startState.setValue(TrackBlock.SHAPE, TrackShape.ZO);
        level.setBlockAndUpdate(start.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(end.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(start, startState);
        level.setBlockAndUpdate(end, endState);
        var enter = new Vec3(1, 0, 0);
        var exit = new Vec3(0, 0, -1);
        var up = new Vec3(0, 1, 0);
        var curve = new BezierConnection(Couple.create(start, end),
                Couple.create(track.getCurveStart(level, start, startState, enter),
                        track.getCurveStart(level, end, endState, exit)),
                Couple.create(enter, exit), Couple.create(up, up), true, false, track.getMaterial());
        ((TrackBlockEntity) level.getBlockEntity(start)).addConnection(curve);
        ((TrackBlockEntity) level.getBlockEntity(end)).addConnection(curve.secondary());
        var graph = new TrackGraph();
        var first = node(test, curve, true, up);
        var second = node(test, curve, false, up);
        graph.addNode(first);
        graph.addNode(second);
        graph.putConnection(first, second, new TrackEdge(first, second, curve, track.getMaterial()));
        graph.putConnection(second, first, new TrackEdge(second, first, curve.secondary(), track.getMaterial()));
        Create.RAILWAYS.putGraph(graph);

        var aim = curve.getPosition(0.5);
        var rail = BlockPos.containing(aim);
        var anchor = rail.above(4);
        var hangerBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger"));
        level.setBlockAndUpdate(anchor, hangerBlock.defaultBlockState());
        var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(anchor);
        var data = hanger.saveWithoutMetadata(level.registryAccess());
        var mount = new CompoundTag();
        var local = aim.subtract(Vec3.atLowerCornerOf(anchor));
        var forward = curve.getPosition(0.51).subtract(curve.getPosition(0.49));
        mount.putDouble("X", local.x);
        mount.putDouble("Y", local.y);
        mount.putDouble("Z", local.z);
        mount.putDouble("Yaw", Math.atan2(forward.x, forward.z));
        data.put("Mount", mount);
        data.putInt("Height", 4);
        hanger.loadWithComponents(data, level.registryAccess());
        hanger.tick();

        // A second line below the curve must not replace the saved mount in the settings lookup.
        for (int x = -1; x <= 1; x++) {
            var below = rail.below().east(x);
            level.setBlockAndUpdate(below.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(below, AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
        }
        var editor = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "CurveSettings"),
                ClientInformation.createDefault()) {
            @Override public OptionalInt openMenu(MenuProvider provider) {
                containerMenu = provider.createMenu(1, getInventory(), this);
                return OptionalInt.of(1);
            }
        };
        verifyFeederClickDoesNotAdjust(test, editor, hanger);
        editor.setShiftKeyDown(true);
        for (int side : new int[]{-1, 1}) {
            editor.setPos(aim.add(0, 0, side * 2));
            test.assertTrue(click(editor, aim) && editor.containerMenu != editor.inventoryMenu
                            && editor.containerMenu.stillValid(editor),
                    "Sneak-right-click did not open the curve's settings from side " + side);
            test.assertTrue(editor.containerMenu.clickMenuButton(editor, 1), "The opened settings could not raise the feeder");
            editor.containerMenu = editor.inventoryMenu;
        }
        test.assertTrue(hanger.height() == 6, "Settings changed the wrong hanger");
        test.assertTrue(!hanger.headPosition().equals(anchor), "The test needs a separate raised feeder head");
        verifyFeederClickDoesNotAdjust(test, editor, hanger);
        var unmounted = curve.getPosition(0.25);
        editor.setPos(unmounted.add(0, 0, -2));
        test.assertTrue(!click(editor, unmounted), "An unmounted section of the curve opened the settings");
        level.setChunkForced(startChunk.x, startChunk.z, false);
        level.setChunkForced(endChunk.x, endChunk.z, false);
        test.succeed();
    }

    private static void verifyFeederClickDoesNotAdjust(GameTestHelper test, ServerPlayer player,
                                                       CatenaryHangerBlockEntity hanger) {
        var level = test.getLevel();
        var head = hanger.headPosition();
        int width = hanger.width();
        int height = hanger.height();
        for (boolean sneaking : new boolean[]{false, true}) {
            player.setShiftKeyDown(sneaking);
            var hit = new BlockHitResult(head.getCenter(), Direction.NORTH, head, false);
            var result = level.getBlockState(head).useWithoutItem(level, player, hit);
            test.assertTrue(result == InteractionResult.PASS && hanger.width() == width && hanger.height() == height,
                    "An empty-hand click on the feeder still adjusted its clearance");
        }
    }

    private static boolean click(ServerPlayer player, Vec3 target) {
        var look = target.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(look.z, look.x)) - 90);
        player.setXRot((float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance())));
        var cell = BlockPos.containing(target);
        var hit = new BlockHitResult(target, Direction.UP, cell, false);
        var click = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, cell, hit);
        NeoForge.EVENT_BUS.post(click);
        return click.isCanceled();
    }

    private static TrackNode node(GameTestHelper test, BezierConnection curve, boolean first, Vec3 normal) {
        var start = curve.starts.get(first);
        var location = new TrackNodeLocation(start).in(test.getLevel());
        location.yOffsetPixels = curve.yOffsetAt(start);
        return new TrackNode(location, first ? 10000 : 10001, normal);
    }
}
