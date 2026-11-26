package org.cha0scollective.powerdock;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

final class CatenaryHangerItem extends BlockItem {
    CatenaryHangerItem(Properties properties) {
        super(PowerDock.CATENARY_HANGER.get(), properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        var player = context.getPlayer();
        if (player == null || player.isSpectator()) return InteractionResult.PASS;
        var target = HangerTrackTarget.pick(level, player);
        if (target == null) return report(player, "message.powerdock.hanger_aim_at_rail");
        var overhead = target.overheadPosition();
        if (target.hanger(level) != null) return report(player, "message.powerdock.hanger_already_placed");
        if (!level.mayInteract(player, target.rail()) || !level.mayInteract(player, overhead)
                || level.isOutsideBuildHeight(overhead) || !level.getBlockState(overhead).isAir()) {
            return report(player, "message.powerdock.hanger_blocked");
        }
        var result = place(BlockPlaceContext.at(new BlockPlaceContext(context), overhead, Direction.UP));
        if (result.consumesAction() && level.getBlockEntity(overhead) instanceof CatenaryHangerBlockEntity hanger) {
            hanger.placeOnRail(target, player);
        }
        return result;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(stack);
        var target = HangerTrackTarget.pick(server, player);
        if (target == null) return new InteractionResultHolder<>(report(player, "message.powerdock.hanger_aim_at_rail"), stack);
        var hit = new BlockHitResult(target.frame().center(), Direction.UP, target.rail(), false);
        return new InteractionResultHolder<>(stack.useOn(new UseOnContext(player, hand, hit)), stack);
    }

    private static InteractionResult report(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
        return InteractionResult.FAIL;
    }
}
