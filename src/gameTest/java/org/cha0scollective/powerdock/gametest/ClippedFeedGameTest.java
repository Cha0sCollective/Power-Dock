package org.cha0scollective.powerdock.gametest;

import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;
import org.cha0scollective.powerdock.ClippedLeadEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("powerdock_tests")
@PrefixGameTestTemplate(false)
public final class ClippedFeedGameTest {
    @GameTest(template = "empty", timeoutTicks = 55)
    public static void survivalClippedPortal(GameTestHelper test) {
        var level = test.getLevel();
        var local = new BlockPos(3, 15, 3);
        test.setBlock(local, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger")));
        var hanger = (CatenaryHangerBlockEntity) level.getBlockEntity(test.absolutePos(local));
        var data = hanger.saveWithoutMetadata(level.registryAccess());
        var mount = new CompoundTag();
        mount.putDouble("X", .5);
        mount.putDouble("Y", -7);
        mount.putDouble("Z", .5);
        mount.putDouble("Yaw", Math.PI/4);
        data.put("Mount", mount);
        data.putString("Support", "PORTAL");
        data.putInt("SupportSide", -1);
        hanger.loadWithComponents(data, level.registryAccess());
        test.runAfterDelay(3, () -> exercise(test, hanger));
    }

    private static void exercise(GameTestHelper test, CatenaryHangerBlockEntity hanger) {
        var level=test.getLevel();
        var player = new net.neoforged.neoforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"Wire installer")) {
            String feedback = "";
            @Override public void displayClientMessage(net.minecraft.network.chat.Component text,boolean actionBar) { feedback=text.getString(); }
        };
        player.setGameMode(GameType.SURVIVAL);
        var copper = BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:insulated_copper_wire"));
        var positive=hanger.fixture().crossbeamCenter().add(Vec3.atLowerCornerOf(hanger.getBlockPos()));
        var across = new Vec3(Math.cos(Math.PI/4),0,-Math.sin(Math.PI/4));
        var depth = new Vec3(-across.z,0,across.x);
        var at=positive.subtract(across.scale(hanger.width()*.5+.1875)).add(0,-3,0);
        player.setShiftKeyDown(true);
        aim(player,at,depth);
        var shortStack = new ItemStack(copper, 1);
        click(player,shortStack);
        test.assertTrue(shortStack.getCount() == 1 && !hanger.saveWithoutMetadata(level.registryAccess()).getBoolean("FeedPosts"),
                "A short stack must leave both the pole and inventory untouched");

        var stack = new ItemStack(copper, 64);
        for (int pin = 0; pin < 2; pin++) {
            int before = stack.getCount();
            boolean picked=click(player,stack);
            var tag = hanger.saveWithoutMetadata(level.registryAccess()).getList("ClippedFeedWires", 10);
            test.assertTrue(tag.size() == pin+1, "Lead "+pin+" was not fitted. Picked="+picked+", feedback="+player.feedback+", target="+at+", eye="+player.getEyePosition());
            var entry = tag.getCompound(pin);
            var wire = (ClippedLeadEntity) level.getEntity(entry.getUUID("Wire"));
            test.assertTrue(wire != null && wire.isInsulated() && wire.getWire() != null
                    && wire.isConnectedTo(hanger.getBlockPos(), pin) && wire.isConnectedTo(hanger.getBlockPos(), 2+pin),
                    "The clips must carry one PG wire between the matching terminals");
            test.assertTrue(before-stack.getCount() == wire.getWireCount() && wire.getWireCount()>1,
                    "The routed length must set the survival cost and PG wire count");

        }
        int remaining = stack.getCount();
        click(player,stack);
        test.assertTrue(stack.getCount() == remaining, "A full pole must not charge for another lead");
        int paid = hanger.saveWithoutMetadata(level.registryAccess()).getList("ClippedFeedWires", 10).getCompound(1).getInt("Count");
        var post = hanger.terminal(hanger.getBlockState(), 3).getOrigin().add(Vec3.atLowerCornerOf(hanger.getBlockPos()));
        aim(player,new Vec3(post.x,at.y,post.z).subtract(depth.scale(2.1/16)),depth);
        click(player,new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:wrench"))));
        test.assertTrue(hanger.saveWithoutMetadata(level.registryAccess()).getList("ClippedFeedWires",10).size() == 1 && player.getInventory().countItem(copper) == paid,
                "Removing the return must refund only that wire and leave the positive lead connected");

        // Refit the return. Each cutter click below starts with two independently connected PG wires.
        aim(player, at, depth);
        click(player, stack);
        var cutters = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:wire_cutter")));
        var pins = hanger.saveWithoutMetadata(level.registryAccess()).getList("ClippedFeedWires", 10);
        test.assertTrue(pins.size() == 2, "Both leads must be connected before cutting");
        var supply = (ClippedLeadEntity) level.getEntity(pins.getCompound(0).getUUID("Wire"));
        var ground = (ClippedLeadEntity) level.getEntity(pins.getCompound(1).getUUID("Wire"));
        player.setShiftKeyDown(false);
        test.assertFalse(click(player, cutters), "Clicking the steel between two leads consumed the cutter action");
        var poleWire = new Vec3(post.x,at.y,post.z).subtract(depth.scale(2.1/16));
        aim(player, poleWire, depth);
        test.assertTrue(click(player, cutters), "A standing right-click missed the return lead on the pole");
        test.assertTrue(ground.isRemoved() && !supply.isRemoved() && supply.getWire() != null,
                "Cutting the return at the pole disturbed the supply");

        player.setShiftKeyDown(true);
        aim(player, at, depth);
        click(player, stack);
        pins = hanger.saveWithoutMetadata(level.registryAccess()).getList("ClippedFeedWires", 10);
        var replacement = (ClippedLeadEntity) level.getEntity(pins.getCompound(1).getUUID("Wire"));
        int expectedDrops = paid + supply.getWireCount();
        player.setShiftKeyDown(false);
        var armWire = positive.subtract(across.scale(1.8)).add(0, .25 + 1.0/16, 0);
        aim(player, armWire, depth);
        test.assertTrue(click(player, cutters), "A standing right-click missed the supply lead on the arm");
        test.assertTrue(supply.isRemoved() && !replacement.isRemoved() && replacement.getWire() != null,
                "Cutting the supply at the arm disturbed the return");
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(hanger.getBlockPos()).inflate(1));
        int dropped = drops.stream().map(ItemEntity::getItem).filter(item -> item.is(copper)).mapToInt(ItemStack::getCount).sum();
        test.assertTrue(dropped == expectedDrops && player.getInventory().countItem(copper) == paid,
                "Each PG cutter action should drop the wire once");
        test.succeed();
    }

    private static void aim(Player player,Vec3 target,Vec3 normal) {
        var eye=target.add(normal.scale(2));
        player.setPos(eye.x,eye.y-player.getEyeHeight(),eye.z);
        player.setYRot((float)Math.toDegrees(Math.atan2(normal.x,-normal.z)));
        player.setXRot(0);
    }
    private static boolean click(Player player,ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(player,InteractionHand.MAIN_HAND)).isCanceled();
    }
}
