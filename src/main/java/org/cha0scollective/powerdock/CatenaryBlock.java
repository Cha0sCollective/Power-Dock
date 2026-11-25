package org.cha0scollective.powerdock;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.base.terminals.RotatedTerminalCollection;

import java.util.function.Supplier;
import javax.annotation.Nullable;

final class CatenaryBlock<T extends ElectricBlockEntity> extends ElectricBlock implements IBE<T> {
    static final BooleanProperty RAISED = BooleanProperty.create("raised");
    static final BooleanProperty CONNECTED = PowerDockBlock.CONNECTED;
    static final BooleanProperty TRACK_ALIGNED = BooleanProperty.create("track_aligned");
    static final VoxelShape CASE = Block.box(2, 0, 2, 14, 5, 14);
    private static final RotatedTerminalCollection POSTS =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
                    .add(new TerminalBoundingBox(IDecoratedTerminal.POSITIVE,
                                    3, 5, 11, 6, 7, 14).withColor(IDecoratedTerminal.RED),
                            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE,
                                    10, 5, 11, 13, 7, 14).withColor(IDecoratedTerminal.BLUE))
                    .build();
    private final Class<T> type;
    private final Supplier<BlockEntityType<T>> blockEntityType;

    CatenaryBlock(Properties properties, Class<T> type, Supplier<BlockEntityType<T>> blockEntityType) {
        super(properties);
        this.type = type;
        this.blockEntityType = blockEntityType;
        registerDefaultState(defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(RAISED, false).setValue(CONNECTED, false).setValue(TRACK_ALIGNED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING, RAISED, CONNECTED, TRACK_ALIGNED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger) hanger.alignOnPlacement();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(TRACK_ALIGNED) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        var facing = BlockStateProperties.HORIZONTAL_FACING;
        return state.setValue(facing, rotation.rotate(state.getValue(facing)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(TRACK_ALIGNED) && level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger
                && hanger.mount() != null) return hanger.mount().shape;
        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return Shapes.or(CASE, POSTS.get(facing, 0).getShape(), POSTS.get(facing, 1).getShape());
    }

    static TerminalBoundingBox post(Direction facing, int index) { return POSTS.get(facing, index); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide && level.mayInteract(player, pos)) {
            if (level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger) {
                hanger.adjust(player.isShiftKeyDown());
                player.displayClientMessage(Component.translatable("message.powerdock.hanger_settings",
                        hanger.width(), hanger.height()), true);
            } else if (level.getBlockEntity(pos) instanceof PantographBlockEntity pickup) {
                if (player.isShiftKeyDown() && player instanceof ServerPlayer serverPlayer) {
                    PantographReceiverMenu.open(serverPlayer, pickup);
                } else {
                    pickup.toggleRaised();
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public int terminalCount() { return 2; }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return index >= 0 && index < 2 ? POSTS.get(state.getValue(BlockStateProperties.HORIZONTAL_FACING), index) : null;
    }

    @Override public Class<T> getBlockEntityClass() { return type; }
    @Override public BlockEntityType<T> getBlockEntityType() { return blockEntityType.get(); }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return type == PantographBlockEntity.class; }
    @Override protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(CONNECTED) ? 15 : 0;
    }
}
