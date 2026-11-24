package org.cha0scollective.powerdock;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(PowerDock.ID)
public final class PowerDock {
    public static final String ID = "powerdock";
    static boolean computerCraft;
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);

    static final Supplier<PowerDockBlock> DOCK = BLOCKS.registerBlock("power_dock",
            properties -> new PowerDockBlock(properties, false), dockProperties());
    static final Supplier<PowerDockBlock> PIN_DOCK = BLOCKS.registerBlock("pin_dock",
            properties -> new PowerDockBlock(properties, true), dockProperties());
    static final Supplier<BlockEntityType<PowerDockBlockEntity>> DOCK_ENTITY =
            BLOCK_ENTITIES.register("power_dock", () -> BlockEntityType.Builder
                    .of(PowerDockBlockEntity::new, DOCK.get(), PIN_DOCK.get()).build(null));
    static final Supplier<RailJumperBlock> RAIL_JUMPER = BLOCKS.registerBlock("rail_jumper",
            properties -> new RailJumperBlock(properties, false), jumperProperties());
    static final Supplier<RailJumperBlock> FLUSH_RAIL_JUMPER = BLOCKS.registerBlock("flush_rail_jumper",
            properties -> new RailJumperBlock(properties, true), jumperProperties());
    static final Supplier<BlockEntityType<RailJumperBlockEntity>> JUMPER_ENTITY =
            BLOCK_ENTITIES.register("rail_jumper", () -> BlockEntityType.Builder
                    .of(RailJumperBlockEntity::new, RAIL_JUMPER.get(), FLUSH_RAIL_JUMPER.get()).build(null));

    private static BlockBehaviour.Properties jumperProperties() {
        return BlockBehaviour.Properties.of().strength(3, 6).requiresCorrectToolForDrops().noOcclusion();
    }

    private static BlockBehaviour.Properties dockProperties() {
        return BlockBehaviour.Properties.of().strength(3, 6).requiresCorrectToolForDrops()
                .noOcclusion().lightLevel(state -> state.getValue(PowerDockBlock.CONNECTED) ? 7 : 0);
    }

    public PowerDock(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        var item = ITEMS.registerSimpleBlockItem("power_dock", DOCK);
        var pinItem = ITEMS.registerSimpleBlockItem("pin_dock", PIN_DOCK);
        var jumperItem = ITEMS.registerSimpleBlockItem("rail_jumper", RAIL_JUMPER);
        var flushJumperItem = ITEMS.registerSimpleBlockItem("flush_rail_jumper", FLUSH_RAIL_JUMPER);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
                event.accept(item.get());
                event.accept(pinItem.get());
                event.accept(jumperItem.get());
                event.accept(flushJumperItem.get());
            }
        });
        computerCraft = ModList.get().isLoaded("computercraft");
        if (computerCraft) ComputerCraftIntegration.register(modBus);
    }
}
