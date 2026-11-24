package org.cha0scollective.powerdock;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.base.terminals.RotatedTerminalCollection;

public final class RailJumperBlock extends ElectricBlock implements IBE<RailJumperBlockEntity> {
    static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    static final BooleanProperty CONNECTED = PowerDockBlock.CONNECTED;
    private static final RotatedTerminalCollection DECK_TERMINALS =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
                    .add(new TerminalBoundingBox(IDecoratedTerminal.POSITIVE,
                                    3, 10, 3, 6, 12, 6).withColor(IDecoratedTerminal.RED),
                            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE,
                                    10, 10, 3, 13, 12, 6).withColor(IDecoratedTerminal.BLUE))
                    .build();
    private static final RotatedTerminalCollection WALL_TERMINALS =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
                    .add(new TerminalBoundingBox(IDecoratedTerminal.POSITIVE,
                                    4, 10, 14, 7, 13, 16).withColor(IDecoratedTerminal.RED),
                            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE,
                                    9, 10, 14, 12, 13, 16).withColor(IDecoratedTerminal.BLUE))
                    .build();
    private static final VoxelShape HOUSING = Shapes.or(Block.box(2, 0, 2, 14, 10, 14),
            Block.box(6, 10, 6, 10, 16, 10), Block.box(6, 10, 12, 10, 11, 14));
    private static final VoxelShape NORTH_PLUG = Block.box(5, 3, 0, 11, 9, 2);
    private static final VoxelShape EAST_PLUG = Block.box(14, 3, 5, 16, 9, 11);
    private static final VoxelShape SOUTH_PLUG = Block.box(5, 3, 14, 11, 9, 16);
    private static final VoxelShape WEST_PLUG = Block.box(0, 3, 5, 2, 9, 11);

    private final boolean flushMount;

    RailJumperBlock(Properties properties, boolean flushMount) {
        super(properties);
        this.flushMount = flushMount;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(CONNECTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CONNECTED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (flushMount) return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        var face = context.getClickedFace();
        if (face.getAxis().isVertical()) {
            face = context.getHorizontalDirection().getOpposite();
        }
        return defaultBlockState().setValue(FACING, face);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (flushMount) return Shapes.block();
        var face = state.getValue(FACING);
        var plug = switch (face) {
            case EAST -> EAST_PLUG;
            case SOUTH -> SOUTH_PLUG;
            case WEST -> WEST_PLUG;
            default -> NORTH_PLUG;
        };
        return Shapes.or(HOUSING, plug, DECK_TERMINALS.get(face, 0).getShape(), DECK_TERMINALS.get(face, 1).getShape());
    }

    Direction dataFace(BlockState state) {
        return flushMount ? state.getValue(FACING).getOpposite() : Direction.UP;
    }

    @Override public int terminalCount() { return 2; }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        var terminals = flushMount ? WALL_TERMINALS : DECK_TERMINALS;
        return index >= 0 && index < 2 ? terminals.get(state.getValue(FACING), index) : null;
    }

    @Override public Class<RailJumperBlockEntity> getBlockEntityClass() { return RailJumperBlockEntity.class; }

    @Override public BlockEntityType<RailJumperBlockEntity> getBlockEntityType() { return PowerDock.JUMPER_ENTITY.get(); }

    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof RailJumperBlockEntity jumper && jumper.connected() ? 15 : 0;
    }
}
