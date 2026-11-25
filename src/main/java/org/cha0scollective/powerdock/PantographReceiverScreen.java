package org.cha0scollective.powerdock;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
final class PantographReceiverScreen extends AbstractContainerScreen<PantographReceiverMenu> {
    private static final int BEZEL = 0xff252b29;
    private static final int PANEL = 0xff454e49;
    private static final int RECESS = 0xff2c3430;
    private static final int TEXT = 0xfff3e9d9;

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) {
        event.register(PowerDock.PANTOGRAPH_RECEIVER_MENU.get(), PantographReceiverScreen::new);
    }

    PantographReceiverScreen(PantographReceiverMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 184;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("gui.powerdock.receiver_clear"), button -> {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, PantographReceiverMenu.CLEAR);
        }).bounds(leftPos + 30, topPos + 66, 55, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.powerdock.receiver_apply"), button -> {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, PantographReceiverMenu.APPLY);
            onClose();
        }).bounds(leftPos + 91, topPos + 66, 55, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BEZEL);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, PANEL);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + 4, 0xffae775c);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, BEZEL);
            graphics.fill(x, y, x + 17, y + 17, RECESS);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(font, title, imageWidth / 2, 11, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.powerdock.receiver_hint"), imageWidth / 2, 28, TEXT);
        graphics.drawString(font, playerInventoryTitle, 8, 92, TEXT, false);
    }
}
