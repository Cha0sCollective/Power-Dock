package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

final class HangerSettingsMenu extends AbstractContainerMenu {
    static final int LOWER = 0;
    static final int RAISE = 1;
    static final int NARROW = 2;
    static final int WIDEN = 3;
    static final int PREVIOUS_SPAN = 4;
    static final int NEXT_SPAN = 5;
    static final int CUT_SPAN = 6;

    @Nullable private final CatenaryHangerBlockEntity hanger;
    private final BlockPos rail;
    @Nullable private ServerPlayer viewer;
    private final DataSlot height = DataSlot.standalone();
    private final DataSlot width = DataSlot.standalone();
    private final DataSlot spanNumber = DataSlot.standalone();
    private final DataSlot spanCount = DataSlot.standalone();
    private final DataSlot confirmCut = DataSlot.standalone();
    @Nullable private CatenaryLines.Link shown;
    @Nullable private UUID pendingCut;

    static void open(ServerPlayer player, CatenaryHangerBlockEntity hanger, BlockPos rail) {
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
            var menu = new HangerSettingsMenu(id, hanger, rail);
            menu.viewer = player;
            return menu;
        },
                Component.translatable("gui.powerdock.hanger_settings")));
    }

    HangerSettingsMenu(int id, Inventory inventory) {
        this(id, null, BlockPos.ZERO);
    }

    private HangerSettingsMenu(int id, @Nullable CatenaryHangerBlockEntity hanger, BlockPos rail) {
        super(PowerDock.HANGER_SETTINGS_MENU.get(), id);
        this.hanger = hanger;
        this.rail = rail;
        addDataSlot(height);
        addDataSlot(width);
        addDataSlot(spanNumber);
        addDataSlot(spanCount);
        addDataSlot(confirmCut);
        syncClearance();
    }

    int height() { return height.get(); }
    int width() { return width.get(); }
    int spanNumber() { return spanNumber.get(); }
    int spanCount() { return spanCount.get(); }
    boolean confirmingCut() { return confirmCut.get() != 0; }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) return true;
        return hanger != null && !hanger.isRemoved() && !hanger.isChunkUnloaded() && hanger.getLevel() == player.level()
                && !player.isSpectator() && player.canInteractWithBlock(rail, 0.75)
                && player.level().mayInteract(player, rail) && player.level().mayInteract(player, hanger.getBlockPos());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (hanger == null || !stillValid(player)) return false;
        if (id == PREVIOUS_SPAN || id == NEXT_SPAN || id == CUT_SPAN) return selectSpan(player, id);
        int nextHeight = hanger.height();
        int nextWidth = hanger.width();
        switch (id) {
            case LOWER -> nextHeight = Math.max(4, nextHeight - 1);
            case RAISE -> nextHeight = Math.min(12, nextHeight + 1);
            case NARROW -> nextWidth = Math.max(3, nextWidth - 2);
            case WIDEN -> nextWidth = Math.min(13, nextWidth + 2);
            default -> { return false; }
        }
        if (!hanger.setClearance(nextHeight, nextWidth, player)) {
            player.displayClientMessage(Component.translatable("message.powerdock.hanger_blocked"), true);
        }
        broadcastChanges();
        return true;
    }

    private List<CatenaryLines.Link> spans() {
        if (hanger == null || !(hanger.getLevel() instanceof ServerLevel server)) return List.of();
        return List.copyOf(CatenaryLines.get(server).attached(hanger.getBlockPos()));
    }

    private boolean selectSpan(Player player, int id) {
        var links = spans();
        if (links.isEmpty()) return false;
        int index = shown == null ? -1 : links.indexOf(shown);
        if (id == CUT_SPAN) {
            if (index < 0 || !(player.level() instanceof ServerLevel level)) return false;
            var link = links.get(index);
            if (!level.mayInteract(player, link.opposite(hanger.getBlockPos()))) {
                player.displayClientMessage(Component.translatable("message.powerdock.catenary_protected_span"), true);
                return false;
            }
            if (link.id.equals(pendingCut)) {
                CatenaryLines.get(level).cut(link);
                pendingCut = null;
            } else {
                pendingCut = link.id;
            }
        } else {
            pendingCut = null;
            int next = index < 0 ? 0 : Math.floorMod(index + (id == NEXT_SPAN ? 1 : -1), links.size());
            show(links.get(next));
        }
        broadcastChanges();
        return true;
    }

    private void show(@Nullable CatenaryLines.Link link) {
        if (shown != link && viewer != null) CatenaryWireUse.showMenuPreview(viewer, containerId, link);
        shown = link;
    }

    @Override
    public void broadcastChanges() {
        syncClearance();
        if (viewer != null) {
            var links = spans();
            if (shown == null || !links.contains(shown)) {
                pendingCut = null;
                show(links.isEmpty() ? null : links.getFirst());
            }
            spanCount.set(links.size());
            spanNumber.set(shown == null ? 0 : links.indexOf(shown) + 1);
            confirmCut.set(pendingCut == null ? 0 : 1);
        }
        super.broadcastChanges();
    }

    private void syncClearance() {
        if (hanger != null) {
            height.set(hanger.height());
            width.set(hanger.width());
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
