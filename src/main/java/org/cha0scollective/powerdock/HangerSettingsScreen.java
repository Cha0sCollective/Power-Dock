package org.cha0scollective.powerdock;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

final class HangerSettingsScreen extends AbstractContainerScreen<HangerSettingsMenu> {
    private static final int TEXT = 0xfff3e9d9;

    HangerSettingsScreen(HangerSettingsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 146;
    }

    @Override
    protected void init() {
        super.init();
        button(10, 28, "−", HangerSettingsMenu.LOWER);
        button(imageWidth - 30, 28, "+", HangerSettingsMenu.RAISE);
        button(10, 59, "−", HangerSettingsMenu.NARROW);
        button(imageWidth - 30, 59, "+", HangerSettingsMenu.WIDEN);
        button(10, 90, "‹", HangerSettingsMenu.PREVIOUS_SPAN);
        button(imageWidth - 30, 90, "›", HangerSettingsMenu.NEXT_SPAN);
        var cut = addRenderableWidget(Button.builder(Component.translatable("gui.powerdock.hanger_cut_span"), ignored ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, HangerSettingsMenu.CUT_SPAN))
                .bounds(leftPos + 20, topPos + 116, imageWidth - 40, 20).build());
        cut.active = false;
        cutButton = cut;
    }

    private Button cutButton;

    @Override
    protected void containerTick() {
        super.containerTick();
        cutButton.active = menu.spanCount() > 0;
        cutButton.setMessage(Component.translatable(menu.confirmingCut()
                ? "gui.powerdock.hanger_confirm_cut" : "gui.powerdock.hanger_cut_span"));
    }

    private void button(int x, int y, String text, int id) {
        addRenderableWidget(Button.builder(Component.literal(text), button -> {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }).bounds(leftPos + x, topPos + y, 20, 20).build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff252b29);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xff454e49);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + 4, 0xffae775c);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(font, title, imageWidth / 2, 12, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.powerdock.hanger_height", menu.height()), imageWidth / 2, 34, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.powerdock.hanger_width", menu.width()), imageWidth / 2, 65, TEXT);
        var selection = menu.spanCount() > 0 ? Component.translatable("gui.powerdock.hanger_span_of", menu.spanNumber(), menu.spanCount())
                : Component.translatable("gui.powerdock.hanger_no_spans");
        graphics.drawCenteredString(font, selection, imageWidth / 2, 96, TEXT);
    }

    @EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    static final class Screens {
        @SubscribeEvent
        public static void register(RegisterMenuScreensEvent event) {
            event.register(PowerDock.HANGER_SETTINGS_MENU.get(), HangerSettingsScreen::new);
        }
    }

    @EventBusSubscriber(modid = PowerDock.ID, value = Dist.CLIENT)
    static final class RailClicks {
        @SubscribeEvent
        public static void useCurvedRail(PlayerInteractEvent.RightClickEmpty event) {
            var player = event.getEntity();
            if (event.getHand() == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && player.getMainHandItem().isEmpty()) {
                PacketDistributor.sendToServer(HangerRailControls.OpenFromRail.INSTANCE);
            }
        }
    }
}
