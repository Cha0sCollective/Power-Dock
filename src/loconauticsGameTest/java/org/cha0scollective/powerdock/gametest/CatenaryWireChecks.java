package org.cha0scollective.powerdock.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.UUID;

final class CatenaryWireChecks {
    private CatenaryWireChecks() {}

    static void link(GameTestHelper test, BlockPos first, BlockPos second, boolean checkUnlink) {
        var level = test.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), "CatenaryTest");
        var player = new ServerPlayer(level.getServer(), level, profile,
                ClientInformation.createDefault()) {
            @Override
            public void displayClientMessage(Component message, boolean overlay) {}
        };
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                CommonListenerCookie.createInitial(profile, false));
        try {
            checkLink(test, player, first, second, checkUnlink);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkLink(GameTestHelper test, ServerPlayer player, BlockPos first, BlockPos second, boolean checkUnlink) {
        var wireItem = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("powergrid", "wire"));
        var wire = new ItemStack(wireItem, 16);
        test.assertTrue(!wire.isEmpty(), "Power Grid Copper Wire was not found");
        var metadata = new CompoundTag();
        metadata.putString("KeepMe", "existing data");
        wire.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
        var ordinaryWire = wire.copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, ordinaryWire);
        click(player, first, false);
        test.assertTrue(ordinaryWire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().equals(metadata),
                "Ordinary Copper Wire use started an overhead span");
        player.setItemInHand(InteractionHand.MAIN_HAND, wire);
        confirmLink(test, player, first, second);
        if (checkUnlink) {
            test.assertTrue(click(player, second, true) && click(player, second, true), "Could not preview unlink");
            test.assertTrue(previous(test, second).contains("Previous"), "Unlink skipped confirmation");
            test.assertTrue(click(player, second, true), "Could not confirm unlink");
            test.assertTrue(!previous(test, second).contains("Previous"), "Unlink left the destination connected");
            confirmLink(test, player, first, second);
        }
        test.assertTrue(wire.getCount() == 16
                        && wire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().equals(metadata),
                "Span linking changed the Copper Wire stack or unrelated data");
    }

    private static void confirmLink(GameTestHelper test, ServerPlayer player, BlockPos first, BlockPos second) {
        test.assertTrue(click(player, first, true) && click(player, second, true), "Could not select and preview the span");
        test.assertTrue(!previous(test, second).contains("Previous"), "The preview already linked the hangers");
        test.assertTrue(click(player, second, true) && previous(test, second).contains("Previous")
                && previous(test, second).getLong("Previous") == first.asLong(), "Confirm did not link the two hangers");
    }

    private static CompoundTag previous(GameTestHelper test, BlockPos pos) {
        var level = test.getLevel();
        return level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess());
    }

    private static boolean click(ServerPlayer player, BlockPos pos, boolean sneak) {
        player.setShiftKeyDown(sneak);
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND,
                pos, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false))).isCanceled();
    }
}
