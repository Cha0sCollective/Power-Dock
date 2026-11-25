package org.cha0scollective.powerdock.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalInt;
import java.util.UUID;

public final class PantographReceiverChecks {
    private static final ResourceLocation RECEIVER_MENU = ResourceLocation.fromNamespaceAndPath("powerdock", "pantograph_receiver");
    private static final int APPLY = 0;
    private static final int CLEAR = 1;

    private PantographReceiverChecks() {}

    public static void bind(GameTestHelper test, BlockPos pos) {
        var opened = open(test, pos, pos.getCenter());
        var menu = opened.menu();
        var level = test.getLevel();
        var pickup = level.getBlockEntity(pos);
        menu.setCarried(new ItemStack(Items.COPPER_INGOT, 16));
        menu.clicked(0, 0, ClickType.PICKUP, opened.player());
        test.assertTrue(menu.getCarried().getCount() == 16 && menu.getSlot(0).getItem().getCount() == 1,
                "The frequency slot must copy an item without consuming it");
        menu.setCarried(new ItemStack(Items.REDSTONE));
        menu.clicked(1, 0, ClickType.PICKUP, opened.player());
        test.assertTrue(!pickup.saveWithoutMetadata(level.registryAccess()).contains("ReceiverFirst"),
                "The draft changed the receiver before Apply");
        test.assertTrue(menu.clickMenuButton(opened.player(), APPLY), "Couldn't apply the receiver frequency");
    }

    public static void verifyOnTrain(GameTestHelper test, BlockPos pos, Vec3 worldCenter) {
        var opened = open(test, pos, worldCenter);
        var menu = opened.menu();
        test.assertTrue(menu.stillValid(opened.player()), "Receiver menu closed while in reach of the assembled train");
        test.assertTrue(menu.getSlot(0).getItem().is(Items.COPPER_INGOT) && menu.getSlot(1).getItem().is(Items.REDSTONE),
                "The receiver binding didn't survive assembling the carriage");
        test.assertTrue(menu.clickMenuButton(opened.player(), CLEAR) && !menu.getSlot(0).hasItem(),
                "Clear didn't empty the draft slots");
    }

    public static void toggleWithEmptyHand(GameTestHelper test, BlockPos pos) {
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        test.getLevel().getBlockState(pos).useWithoutItem(test.getLevel(), player, hit(pos));
    }

    private static Opened open(GameTestHelper test, BlockPos pos, Vec3 center) {
        var level = test.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "PantographTest"),
                ClientInformation.createDefault()) {
            @Override
            public OptionalInt openMenu(MenuProvider provider) {
                containerMenu = provider.createMenu(1, getInventory(), this);
                return OptionalInt.of(1);
            }
        };
        player.setPos(center.x, center.y - 1, center.z + 1);
        player.setShiftKeyDown(true);
        var state = level.getBlockState(pos);
        var raised = (BooleanProperty) state.getBlock().getStateDefinition().getProperty("raised");
        boolean wasRaised = state.getValue(raised);
        state.useWithoutItem(level, player, hit(pos));
        test.assertTrue(player.containerMenu != player.inventoryMenu
                        && RECEIVER_MENU.equals(BuiltInRegistries.MENU.getKey(player.containerMenu.getType())),
                "Sneak-use didn't open the receiver menu");
        test.assertTrue(level.getBlockState(pos).getValue(raised) == wasRaised,
                "Opening receiver setup moved the pantograph");
        return new Opened(player, player.containerMenu);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(pos.getCenter(), Direction.UP, pos, false);
    }

    private record Opened(ServerPlayer player, AbstractContainerMenu menu) {}
}
