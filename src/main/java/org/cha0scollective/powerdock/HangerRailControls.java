package org.cha0scollective.powerdock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = PowerDock.ID)
final class HangerRailControls {
    private HangerRailControls() {}

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(OpenFromRail.TYPE, OpenFromRail.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) openAtRail(player, true);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useRail(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getUseBlock().isFalse()
                || !(event.getEntity() instanceof ServerPlayer player) || !openAtRail(player, false)) return;
        event.setCancellationResult(InteractionResult.CONSUME);
        event.setCanceled(true);
    }

    private static boolean openAtRail(ServerPlayer player, boolean curvedOnly) {
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty() || player.isSpectator()) return false;
        var level = player.serverLevel();
        var ray = HangerTrackTarget.trace(level, player);
        if (ray == null) return false;
        var target = HangerRailTarget.pick(level, ray, curvedOnly);
        if (target == null || !level.mayInteract(player, target.rail())
                || !level.mayInteract(player, target.hanger().getBlockPos())) return false;
        HangerSettingsMenu.open(player, target.hanger(), target.rail());
        return true;
    }

    record OpenFromRail() implements CustomPacketPayload {
        static final OpenFromRail INSTANCE = new OpenFromRail();
        static final Type<OpenFromRail> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "hanger_rail"));
        static final StreamCodec<RegistryFriendlyByteBuf, OpenFromRail> CODEC = StreamCodec.unit(INSTANCE);

        @Override public Type<OpenFromRail> type() { return TYPE; }
    }
}
