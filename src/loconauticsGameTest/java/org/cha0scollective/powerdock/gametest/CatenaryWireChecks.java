package org.cha0scollective.powerdock.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.cha0scollective.powerdock.CatenaryHangerBlockEntity;

import java.util.UUID;
import java.util.OptionalInt;
import java.util.function.Consumer;

final class CatenaryWireChecks {
    private CatenaryWireChecks() {}

    static void link(GameTestHelper test, BlockPos first, BlockPos second, boolean checkUnlink) {
        link(test, first, second, checkUnlink, false);
    }

    static void extendFromNewHanger(GameTestHelper test, BlockPos first, BlockPos second) {
        link(test, first, second, true, true);
    }

    private static void link(GameTestHelper test, BlockPos first, BlockPos second, boolean checkUnlink, boolean reverse) {
        withPlayer(test, player -> checkLink(test, player, first, second, checkUnlink, reverse));
    }

    static void linkVia(GameTestHelper test, BlockPos first, BlockPos waypoint, BlockPos second) {
        withPlayer(test, player -> {
            var wire = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:wire")));
            player.setItemInHand(InteractionHand.MAIN_HAND, wire);
            test.assertTrue(click(player, first, true) && click(player, second, true)
                            && selection(wire).getBoolean("NeedsWaypoint") && !selection(wire).contains("Preview"),
                    "The turnout should ask for a waypoint while either route is possible");
            test.assertTrue(click(player, waypoint, true) && selection(wire).contains("Preview"),
                    "Selecting a rail on the intended branch did not preview the route");
            test.assertTrue(click(player, second, true) && selection(wire).isEmpty() && linked(test, first, second),
                    "Confirming the preview did not link the selected branch");
        });
    }

    static void rejectDuplicate(GameTestHelper test, BlockPos first, BlockPos waypoint, BlockPos second) {
        withPlayer(test, null, player -> {
            var wire = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("powergrid:wire")));
            player.setItemInHand(InteractionHand.MAIN_HAND, wire);
            test.assertTrue(click(player, first, true) && click(player, second, true)
                            && click(player, waypoint, true) && !selection(wire).contains("Confirm"),
                    "Selecting the same route in reverse offered to add a duplicate span");
        });
    }

    static void cutFromMenu(GameTestHelper test, BlockPos rail) {
        withPlayer(test, player -> {
            var aim = rail.getBottomCenter().add(0, 0.125, 0);
            player.setPos(aim.x - 1.5, aim.y + 0.25, aim.z);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
            player.setYHeadRot(player.getYRot());
            test.assertTrue(click(player, rail, true) && player.containerMenu != player.inventoryMenu,
                    "Could not open the settings for a connected hanger");
            var menu = player.containerMenu;
            menu.broadcastChanges();
            test.assertTrue(menu.clickMenuButton(player, 6) && menu.clickMenuButton(player, 6),
                    "Could not confirm the selected span cut");
        });
    }

    private static void withPlayer(GameTestHelper test, Consumer<ServerPlayer> action) {
        withPlayer(test, "catenary_preview", action);
    }

    static void watchWireChunk(GameTestHelper test, BlockPos pos) {
        withPlayer(test, "catenary_chunk_wires", player -> {
            var level = test.getLevel();
            var chunkPos = new ChunkPos(pos);
            var chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            test.assertTrue(chunk != null, "The test rail chunk was unavailable");
            NeoForge.EVENT_BUS.post(new ChunkWatchEvent.Sent(player, chunk, level));
        });
    }

    private static void withPlayer(GameTestHelper test, String payload, Consumer<ServerPlayer> action) {
        var level = test.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), "CatenaryTest");
        var player = new ServerPlayer(level.getServer(), level, profile,
                ClientInformation.createDefault()) {
            @Override
            public void displayClientMessage(Component message, boolean overlay) {}

            @Override
            public OptionalInt openMenu(MenuProvider provider) {
                containerMenu = provider.createMenu(1, getInventory(), this);
                return OptionalInt.of(1);
            }
        };
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                CommonListenerCookie.createInitial(profile, false));
        try {
            NetworkRegistry.configureMockConnection(connection);
            action.accept(player);
            if (payload == null) return;
            var previewId = ResourceLocation.fromNamespaceAndPath("powerdock", payload);
            test.assertTrue(channel.outboundMessages().stream().anyMatch(message ->
                            message instanceof ClientboundCustomPayloadPacket packet && packet.payload().type().id().equals(previewId)),
                    "The player did not receive the " + payload + " packet");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkLink(GameTestHelper test, ServerPlayer player, BlockPos first, BlockPos second,
                                  boolean checkUnlink, boolean reverse) {
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
        var start = reverse ? second : first;
        var end = reverse ? first : second;
        if (checkUnlink) {
            test.assertTrue(click(player, start, true) && selection(wire).getLong("From") == start.asLong(),
                    "The wire did not retain an active first selection");
            test.assertTrue(click(player, end, true) && selection(wire).contains("Preview"), "No preview was selected");
            var cancel = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND));
            test.assertTrue(cancel.isCanceled() && selection(wire).isEmpty() && !linked(test, first, second),
                    "Canceling the preview did not clear the selection without placing a span");
        }
        confirmLink(test, player, first, second, start, end);
        if (checkUnlink) {
            test.assertTrue(click(player, second, true) && click(player, second, true), "Could not preview unlink");
            test.assertTrue(linked(test, first, second), "Unlink skipped confirmation");
            test.assertTrue(click(player, second, true), "Could not confirm unlink");
            test.assertTrue(!linked(test, first, second), "Unlink left the destination connected");
            confirmLink(test, player, first, second, start, end);
        }
        test.assertTrue(wire.getCount() == 16
                        && wire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().equals(metadata),
                "Span linking changed the Copper Wire stack or unrelated data");
    }

    private static void confirmLink(GameTestHelper test, ServerPlayer player, BlockPos first, BlockPos second,
                                    BlockPos start, BlockPos end) {
        test.assertTrue(click(player, start, true) && click(player, end, true) && selection(player.getMainHandItem()).contains("Preview"),
                "Could not select and preview the span");
        test.assertTrue(!linked(test, first, second), "The preview already linked the hangers");
        test.assertTrue(click(player, end, true) && linked(test, first, second), "Confirm did not link the two hangers");
    }

    private static CompoundTag selection(ItemStack wire) {
        return wire.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("powerdock:CatenaryRoute");
    }

    private static boolean linked(GameTestHelper test, BlockPos from, BlockPos to) {
        return test.getLevel().getBlockEntity(from) instanceof CatenaryHangerBlockEntity hanger && hanger.linkedTo(to);
    }

    private static boolean click(ServerPlayer player, BlockPos pos, boolean sneak) {
        player.setShiftKeyDown(sneak);
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND,
                pos, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false))).isCanceled();
    }
}
