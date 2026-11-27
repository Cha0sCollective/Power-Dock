package org.cha0scollective.powerdock;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    static final Supplier<EntityType<ClippedLeadEntity>> CLIPPED_LEAD = ENTITIES.register("clipped_lead",
            () -> EntityType.Builder.<ClippedLeadEntity>of(ClippedLeadEntity::new, MobCategory.MISC)
                    .sized(.1f, .1f).clientTrackingRange(8).updateInterval(Integer.MAX_VALUE).build("powerdock:clipped_lead"));
    static final Supplier<MenuType<PantographReceiverMenu>> PANTOGRAPH_RECEIVER_MENU =
            MENUS.register("pantograph_receiver", () -> new MenuType<>(PantographReceiverMenu::new, FeatureFlags.DEFAULT_FLAGS));
    static final Supplier<MenuType<HangerSettingsMenu>> HANGER_SETTINGS_MENU =
            MENUS.register("hanger_settings", () -> new MenuType<>(HangerSettingsMenu::new, FeatureFlags.DEFAULT_FLAGS));

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
    static final Supplier<CatenaryBlock<CatenaryHangerBlockEntity>> CATENARY_HANGER = BLOCKS.registerBlock("catenary_hanger",
            properties -> new CatenaryBlock<>(properties, CatenaryHangerBlockEntity.class, () -> PowerDock.CATENARY_HANGER_ENTITY.get()),
            jumperProperties().dynamicShape());
    static final Supplier<HangerHeadBlock> HANGER_HEAD = BLOCKS.registerBlock("catenary_hanger_head",
            HangerHeadBlock::new, jumperProperties().dynamicShape().noLootTable());
    static final Supplier<BlockEntityType<HangerHeadBlockEntity>> HANGER_HEAD_ENTITY =
            BLOCK_ENTITIES.register("catenary_hanger_head", () -> BlockEntityType.Builder
                    .of(HangerHeadBlockEntity::new, HANGER_HEAD.get()).build(null));
    static final Supplier<CatenaryBlock<PantographBlockEntity>> PANTOGRAPH = BLOCKS.registerBlock("pantograph",
            properties -> new CatenaryBlock<>(properties, PantographBlockEntity.class, () -> PowerDock.PANTOGRAPH_ENTITY.get()),
            jumperProperties());
    static final Supplier<BlockEntityType<CatenaryHangerBlockEntity>> CATENARY_HANGER_ENTITY =
            BLOCK_ENTITIES.register("catenary_hanger", () -> BlockEntityType.Builder
                    .of(CatenaryHangerBlockEntity::new, CATENARY_HANGER.get()).build(null));
    static final Supplier<BlockEntityType<PantographBlockEntity>> PANTOGRAPH_ENTITY =
            BLOCK_ENTITIES.register("pantograph", () -> BlockEntityType.Builder
                    .of(PantographBlockEntity::new, PANTOGRAPH.get()).build(null));

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
        MENUS.register(modBus);
        ENTITIES.register(modBus);
        var item = ITEMS.registerSimpleBlockItem("power_dock", DOCK);
        var pinItem = ITEMS.registerSimpleBlockItem("pin_dock", PIN_DOCK);
        var jumperItem = ITEMS.registerSimpleBlockItem("rail_jumper", RAIL_JUMPER);
        var flushJumperItem = ITEMS.registerSimpleBlockItem("flush_rail_jumper", FLUSH_RAIL_JUMPER);
        var hanger = ITEMS.register("catenary_hanger", () -> new CatenaryHangerItem(new Item.Properties()));
        var pantograph = ITEMS.registerSimpleBlockItem("pantograph", PANTOGRAPH);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
                event.accept(item.get());
                event.accept(pinItem.get());
                event.accept(jumperItem.get());
                event.accept(flushJumperItem.get());
                event.accept(hanger.get());
                event.accept(pantograph.get());
            }
        });
        modBus.addListener(HangerRailControls::registerPayloads);
        modBus.addListener(CatenaryWireUse::registerPayloads);
        modBus.addListener(CatenaryWireSync::registerPayloads);
        computerCraft = ModList.get().isLoaded("computercraft");
        if (computerCraft) ComputerCraftIntegration.register(modBus);
    }
}
