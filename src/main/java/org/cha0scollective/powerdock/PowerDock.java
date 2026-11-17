package org.cha0scollective.powerdock;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(PowerDock.ID)
public final class PowerDock {
    public static final String ID = "powerdock";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);

    static final Supplier<PowerDockBlock> DOCK = BLOCKS.registerBlock("power_dock", PowerDockBlock::new,
            BlockBehaviour.Properties.of().strength(3, 6).requiresCorrectToolForDrops()
                    .noOcclusion().lightLevel(state -> state.getValue(PowerDockBlock.CONNECTED) ? 7 : 0));
    static final Supplier<BlockEntityType<PowerDockBlockEntity>> DOCK_ENTITY =
            BLOCK_ENTITIES.register("power_dock", () -> BlockEntityType.Builder
                    .of(PowerDockBlockEntity::new, DOCK.get()).build(null));

    public PowerDock(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        var item = ITEMS.registerSimpleBlockItem("power_dock", DOCK);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) event.accept(item.get());
        });
    }
}
