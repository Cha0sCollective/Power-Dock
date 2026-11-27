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
        imageWidth = 244;
        imageHeight = 210;
    }

    @Override
    protected void init() {
        super.init();
        pair(26, 7, 8);
        pair(51, HangerSettingsMenu.LOWER, HangerSettingsMenu.RAISE);
        pair(76, HangerSettingsMenu.NARROW, HangerSettingsMenu.WIDEN);
        pair(101, 10, 11);
        pair(126, 12, 13);
        pair(151, HangerSettingsMenu.PREVIOUS_SPAN, HangerSettingsMenu.NEXT_SPAN);
        sideButton = action(163, 82, 73, 9);
        feedButton = action(163, 105, 73, 14);
        cutButton = action(15, 179, 215, HangerSettingsMenu.CUT_SPAN);
        updateControls();
    }

    private final Button[] controls = new Button[15];
    private Button sideButton, feedButton, cutButton;
    private FrameStyle lastPreview;
    private int previewWidth, previewHeight;
    private HangerFrame preview;

    private Button action(int x, int y, int width, int id) {
        return controls[id] = addRenderableWidget(Button.builder(Component.empty(), ignored ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id))
                .bounds(leftPos + x, topPos + y, width, 18).build());
    }
    private void pair(int y, int previous, int next) {
        action(8,y,18,previous).setMessage(Component.literal("‹"));
        action(137,y,18,next).setMessage(Component.literal("›"));
    }
    @Override protected void containerTick() {
        super.containerTick();
        updateControls();
    }
    private void updateControls() {
        var s = menu.style();
        controls[10].visible = controls[11].visible = usesReach(s, -1);
        controls[12].visible = controls[13].visible = usesReach(s, 1);
        sideButton.visible = s.sided();
        sideButton.setMessage(Component.translatable(s.side() < 0 ? "gui.powerdock.mount_left" : "gui.powerdock.mount_right"));
        feedButton.visible = s.kind() != FrameStyle.Kind.FLOATING;
        feedButton.setMessage(Component.translatable(menu.clippedFeed() ? "gui.powerdock.feed_clipped" : "gui.powerdock.feed_" + (s.terminals() ? "on" : "off")));
        cutButton.active = menu.spanCount() > 0;
        cutButton.setMessage(Component.translatable(menu.confirmingCut() ? "gui.powerdock.hanger_confirm_cut" : "gui.powerdock.hanger_cut_span"));
    }

    private boolean usesReach(FrameStyle s, int side) {
        if (s.kind() == FrameStyle.Kind.FLOATING) return false;
        if (s.kind() == FrameStyle.Kind.CEILING) return side > 0;
        return s.kind() == FrameStyle.Kind.PORTAL || s.side() == side;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff252b29);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xff454e49);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + 4, 0xffae775c);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(font, title, imageWidth / 2, 10, TEXT);
        var style = menu.style();
        graphics.drawCenteredString(font, Component.translatable(style.label()), 81, 31, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.powerdock.hanger_height", menu.height()), 81, 56, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.powerdock.hanger_width", menu.width()), 81, 81, TEXT);
        for (int side : new int[]{-1,1}) if (usesReach(style, side)) {
            String key = style.kind() == FrameStyle.Kind.PORTAL ? (side < 0 ? "left_reach" : "right_reach") : "reach";
            graphics.drawCenteredString(font, Component.translatable("gui.powerdock." + key, style.reach(side)),
                    81, side < 0 ? 106 : 131, TEXT);
        }
        var selection = menu.spanCount() > 0 ? Component.translatable("gui.powerdock.hanger_span_of", menu.spanNumber(), menu.spanCount())
                : Component.translatable("gui.powerdock.hanger_no_spans");
        graphics.drawCenteredString(font, selection, 81, 156, TEXT);
        if (!style.equals(lastPreview) || menu.height() != previewHeight || menu.width() != previewWidth) {
            lastPreview = style;
            previewHeight = menu.height();
            previewWidth = menu.width();
            var origin = net.minecraft.world.phys.Vec3.ZERO;
            var across = new net.minecraft.world.phys.Vec3(1,0,0);
            preview = new HangerFrame(new HangerFixture(origin,across.scale(CatenarySpan.HALF_SPACING),
                    origin, -previewHeight, previewWidth),style,across);
        }
        if(menu.rejected()>=0) {
            var region = menu.rejected()<2 ? "head" : menu.rejected()<4 ? "plate" : "tube";
            graphics.drawCenteredString(font,Component.translatable("gui.powerdock.blocked_"+region,menu.rejected()<4 ?
                    (menu.rejected()%2==0 ? "+" : "−") : Integer.toString(menu.rejected()-3)),122,195,0xffffad91);
        }
        graphics.fill(161,23,237,78,0xff242b2a);
        double scale = Math.min(3.8, 47.0 / (Math.max(2,previewHeight) + Math.max(0,Math.max(style.leftReach(),style.rightReach())) + 3));
        for (var part : preview.members) {
            var a = part.start();
            var b = part.point(0,0,1);
            int steps = Math.max(1,(int)Math.ceil(part.length()*scale));
            for(int i=0;i<=steps;i++) {
                var p = a.lerp(b,(double)i/steps);
                int x = 198+(int)Math.round(p.x*scale);
                int y = (style.kind() == FrameStyle.Kind.CEILING ? 66 : 36)-(int)Math.round(p.y*scale);
                graphics.fill(x-1,y-1,x+1,y+1,part.color() | 0x303030);
            }
        }
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
