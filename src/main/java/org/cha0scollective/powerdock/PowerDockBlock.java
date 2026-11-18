package org.cha0scollective.powerdock;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import org.joml.Quaternionf;
import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.base.terminals.RotatedTerminalCollection;

import java.util.Map;

public final class PowerDockBlock extends ElectricBlock implements IBE<PowerDockBlockEntity> {
    static final DirectionProperty FACING = BlockStateProperties.FACING;
    static final BooleanProperty CONNECTED = BooleanProperty.create("connected");
    static final BooleanProperty DEPLOYED = BooleanProperty.create("deployed");
    static final int TERMINAL_COUNT = 2;

    private static final RotatedTerminalCollection TERMINALS =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.values())
                    .add(new TerminalBoundingBox(IDecoratedTerminal.POSITIVE,
                            4, 14, 9, 7, 16, 13).withColor(IDecoratedTerminal.RED),
                         new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE,
                            9, 14, 9, 12, 16, 13).withColor(IDecoratedTerminal.BLUE))
                    .build();
    private static final RotatedTerminalCollection HOUSING =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.values())
                    .add(new TerminalBoundingBox(Component.empty(), 2, 3, 4, 14, 14, 14),
                         new TerminalBoundingBox(Component.empty(), 3, 3, 2, 13, 13, 4),
                         new TerminalBoundingBox(Component.empty(), 2, 2, 14, 14, 14, 16),
                         new TerminalBoundingBox(Component.empty(), 4, 4, 0, 12, 12, 2))
                    .build();
    private final Map<BlockState, VoxelShape> shapes;

    PowerDockBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH)
                .setValue(CONNECTED, false).setValue(DEPLOYED, false));
        shapes = getShapeForEachState(state -> {
            Direction facing = state.getValue(FACING);
            return Shapes.or(HOUSING.get(facing, 0).getShape(), HOUSING.get(facing, 1).getShape(),
                    HOUSING.get(facing, 2).getShape(), HOUSING.get(facing, 3).getShape(),
                    TERMINALS.get(facing, 0).getShape(), TERMINALS.get(facing, 1).getShape());
        });
    }

    static Quaternionf modelRotation(Direction facing) {
        float rightAngle = (float) (Math.PI / 2);
        return switch (facing) {
            case NORTH -> new Quaternionf();
            case EAST -> new Quaternionf().rotationY(-rightAngle);
            case SOUTH -> new Quaternionf().rotationY(2 * rightAngle);
            case WEST -> new Quaternionf().rotationY(rightAngle);
            case UP -> new Quaternionf().rotationX(rightAngle);
            case DOWN -> new Quaternionf().rotationX(-rightAngle);
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CONNECTED, DEPLOYED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
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
        return shapes.get(state);
    }

    @Override
    public int terminalCount() {
        return TERMINAL_COUNT;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return index >= 0 && index < TERMINAL_COUNT ? TERMINALS.get(state.getValue(FACING), index) : null;
    }

    @Override
    public Class<PowerDockBlockEntity> getBlockEntityClass() {
        return PowerDockBlockEntity.class;
    }

    @Override
    public BlockEntityType<PowerDockBlockEntity> getBlockEntityType() {
        return PowerDock.DOCK_ENTITY.get();
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                   BlockPos neighborPos, boolean moved) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moved);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PowerDockBlockEntity dock) {
            dock.releaseIfDisabled(level);
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PowerDockBlockEntity dock && dock.connected() ? 15 : 0;
    }
}
