package org.cha0scollective.powerdock;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

final class PantographReceiverMenu extends AbstractContainerMenu {
    static final int FIRST_X = 69;
    static final int SECOND_X = 91;
    static final int FREQUENCY_Y = 43;
    static final int INVENTORY_Y = 102;
    static final int APPLY = 0;
    static final int CLEAR = 1;
    private static final int FREQUENCY_SLOTS = 2;

    @Nullable private final PantographBlockEntity pickup;
    private final SimpleContainer draft = new SimpleContainer(FREQUENCY_SLOTS);

    static void open(ServerPlayer player, PantographBlockEntity pickup) {
        if (player.isSpectator()) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new PantographReceiverMenu(id, inventory, pickup),
                Component.translatable("gui.powerdock.pantograph_receiver")));
    }

    PantographReceiverMenu(int id, Inventory inventory) {
        this(id, inventory, null);
    }

    PantographReceiverMenu(int id, Inventory inventory, @Nullable PantographBlockEntity pickup) {
        super(PowerDock.PANTOGRAPH_RECEIVER_MENU.get(), id);
        this.pickup = pickup;
        if (pickup != null) {
            draft.setItem(0, pickup.receiverItem(true));
            draft.setItem(1, pickup.receiverItem(false));
        }
        addSlot(new FrequencySlot(0, FIRST_X));
        addSlot(new FrequencySlot(1, SECOND_X));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) return true;
        if (pickup == null || pickup.isRemoved() || pickup.isChunkUnloaded()
                || pickup.getLevel() != player.level() || player.isSpectator()) return false;
        var pos = pickup.getBlockPos();
        var level = player.level();
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) == pickup
                && level.mayInteract(player, pos) && player.canInteractWithBlock(pos, 1);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId < 0 || slotId >= FREQUENCY_SLOTS) {
            super.clicked(slotId, button, clickType, player);
        } else if (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE) {
            draft.setItem(slotId, clickType == ClickType.PICKUP && button == 0
                    ? getCarried().copyWithCount(1) : ItemStack.EMPTY);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        if (slotId >= FREQUENCY_SLOTS && slotId < slots.size()) {
            var stack = getSlot(slotId).getItem();
            if (!stack.isEmpty()) {
                for (int i = 0; i < FREQUENCY_SLOTS; i++) {
                    if (!draft.getItem(i).isEmpty()) continue;
                    draft.setItem(i, stack.copyWithCount(1));
                    break;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!stillValid(player)) return false;
        if (id == CLEAR) {
            draft.clearContent();
            broadcastChanges();
            return true;
        }
        if (id == APPLY && pickup != null) {
            pickup.configureReceiver(draft.getItem(0), draft.getItem(1));
            return true;
        }
        return false;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container != draft;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != draft;
    }

    private final class FrequencySlot extends Slot {
        FrequencySlot(int index, int x) {
            super(draft, index, x, FREQUENCY_Y);
        }

        @Override public boolean isFake() { return true; }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }
}
