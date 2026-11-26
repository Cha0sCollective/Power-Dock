package org.cha0scollective.powerdock.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.cha0scollective.powerdock.HangerHeadBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.CordItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CordItem.class, remap = false)
abstract class HangerCordEndpoints {
    @Redirect(method = "useOn(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Ldev/architectury/event/EventResult;",
            at = @At(value = "NEW", target = "(Lnet/minecraft/core/BlockPos;I)Lorg/patryk3211/powergrid/electricity/wire/BlockWireEndpoint;"))
    private static BlockWireEndpoint powerdock$anchorHangerCord(BlockPos pos, int terminal, Player player,
                                                              InteractionHand hand, BlockPos clicked, Direction face) {
        var entity = player.level().getBlockEntity(pos);
        return new BlockWireEndpoint(entity instanceof HangerHeadBlockEntity head ? head.anchorPosition() : pos, terminal);
    }
}
