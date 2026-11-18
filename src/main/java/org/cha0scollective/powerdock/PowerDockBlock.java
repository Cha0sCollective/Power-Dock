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
    static final BooleanProperty RETRACTING = BooleanProperty.create("retracting");
    static final int PIN_COUNT = 8;
    static final int COMMON_PIN = 9;

    private static final RotatedTerminalCollection POWER_TERMINALS =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.values())
                    .add(new TerminalBoundingBox(IDecoratedTerminal.POSITIVE,
                            4, 14, 9, 7, 16, 13).withColor(IDecoratedTerminal.RED),
                         new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE,
                            9, 14, 9, 12, 16, 13).withColor(IDecoratedTerminal.BLUE))
                    .build();
    private static final RotatedTerminalCollection PIN_TERMINALS = pinTerminals();
    private static final RotatedTerminalCollection HOUSING =
            RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                    .with(Direction.values())
                    .add(new TerminalBoundingBox(Component.empty(), 2, 3, 4, 14, 14, 14),
                         new TerminalBoundingBox(Component.empty(), 3, 3, 2, 13, 13, 4),
                         new TerminalBoundingBox(Component.empty(), 2, 2, 14, 14, 14, 16),
                         new TerminalBoundingBox(Component.empty(), 4, 4, 0, 12, 12, 2),
                         new TerminalBoundingBox(Component.empty(), 1, 3, 0, 15, 13, 2))
                    .build();
    private final Map<BlockState, VoxelShape> shapes;
    private final boolean pinDock;

    PowerDockBlock(Properties properties, boolean pinDock) {
        super(properties);
        this.pinDock = pinDock;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH)
                .setValue(CONNECTED, false).setValue(DEPLOYED, false).setValue(RETRACTING, false));
        shapes = getShapeForEachState(state -> {
            Direction facing = state.getValue(FACING);
            var shape = Shapes.or(HOUSING.get(facing, 0).getShape(), HOUSING.get(facing, 1).getShape(),
                    HOUSING.get(facing, 2).getShape(), HOUSING.get(facing, pinDock ? 4 : 3).getShape());
            for (int index = 0; index < terminalCount(); index++) {
                shape = Shapes.or(shape, terminals().get(facing, index).getShape());
            }
            return shape;
        });
    }

    private static RotatedTerminalCollection pinTerminals() {
        var builder = RotatedTerminalCollection.builder(RotatedTerminalCollection::rotateNorthToFacing)
                .with(Direction.values());
        // The Pinout controller uses native terminal 0 for common and terminals 1–8 for signals.
        builder.add(new TerminalBoundingBox(IDecoratedTerminal.COMMON, 7, 14, 11, 9, 16, 13));
        int[] columns = {2, 5, 9, 12};
        for (int pin = 1; pin <= PIN_COUNT; pin++) {
            int x = columns[(pin - 1) % columns.length];
            int z = pin <= 4 ? 5 : 8;
            builder.add(new TerminalBoundingBox(Component.translatable("terminal.powerdock.pin", pin),
                    x, 14, z, x + 2, 16, z + 2));
        }
        return builder.build();
    }

    private RotatedTerminalCollection terminals() {
        return pinDock ? PIN_TERMINALS : POWER_TERMINALS;
    }

    boolean isPinDock() {
        return pinDock;
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
        builder.add(FACING, CONNECTED, DEPLOYED, RETRACTING);
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
        return pinDock ? COMMON_PIN : 2;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return index >= 0 && index < terminalCount() ? terminals().get(state.getValue(FACING), index) : null;
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
