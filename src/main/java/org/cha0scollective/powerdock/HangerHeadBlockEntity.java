package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;

import javax.annotation.Nullable;

public final class HangerHeadBlockEntity extends BlockEntity implements IElectric {
    HangerHeadBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.HANGER_HEAD_ENTITY.get(), pos, state);
    }

    public BlockPos anchorPosition() {
        return HangerHeadBlock.anchor(worldPosition, getBlockState());
    }

    @Nullable CatenaryHangerBlockEntity hanger() {
        if (level != null && level.getBlockEntity(anchorPosition()) instanceof CatenaryHangerBlockEntity hanger
                && worldPosition.equals(hanger.headPosition())) return hanger;
        return null;
    }

    @Override public int terminalCount() { return 2; }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        var hanger = hanger();
        return index >= 0 && index < 2 && hanger != null && hanger.mount() != null ? hanger.mount().post(index) : null;
    }

    @Override
    public ElectricBehaviour getBehaviour(Level world, BlockPos pos, BlockState state) {
        var hanger = hanger();
        return hanger == null ? null : hanger.getElectricBehaviour();
    }

    @Override
    public InteractionResult onWire(BlockState state, UseOnContext context) {
        var hanger = hanger();
        var player = context.getPlayer();
        if (hanger == null || player == null || !context.getLevel().mayInteract(player, anchorPosition())) {
            return InteractionResult.PASS;
        }
        // Store the stationary anchor as the endpoint so the wire follows the terminals on the next adjustment.
        var hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), anchorPosition(), context.isInside());
        return hanger.onWire(hanger.getBlockState(), new UseOnContext(player, context.getHand(), hit));
    }
}
