package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.serialization.MapCodec;

final class HangerHeadBlock extends BaseEntityBlock {
    private static final MapCodec<HangerHeadBlock> CODEC = simpleCodec(HangerHeadBlock::new);
    private static final int ANCHOR_BIAS = 16;
    private static final IntegerProperty ANCHOR = IntegerProperty.create("anchor", 0, 2 * ANCHOR_BIAS);

    HangerHeadBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ANCHOR, ANCHOR_BIAS));
    }

    @Override protected MapCodec<HangerHeadBlock> codec() { return CODEC; }

    BlockState stateForOffset(int offset) {
        return defaultBlockState().setValue(ANCHOR, ANCHOR_BIAS - offset);
    }

    static BlockPos anchor(BlockPos pos, BlockState state) {
        return pos.above(state.getValue(ANCHOR) - ANCHOR_BIAS);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ANCHOR);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HangerHeadBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(anchor(pos, state)) instanceof CatenaryHangerBlockEntity hanger && hanger.mount() != null
                ? hanger.mount().outline : CatenaryBlock.HANGER_CASE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(anchor(pos, state)) instanceof CatenaryHangerBlockEntity hanger && hanger.mount() != null
                ? hanger.mount().shape : CatenaryBlock.HANGER_CASE;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!level.isClientSide && !replacement.is(this)) {
            var base = anchor(pos, state);
            if (level.getBlockEntity(base) instanceof CatenaryHangerBlockEntity hanger && !hanger.isRemoved()
                    && pos.equals(hanger.headPosition())) level.destroyBlock(base, true);
        }
        super.onRemove(state, level, pos, replacement, moved);
    }
}
