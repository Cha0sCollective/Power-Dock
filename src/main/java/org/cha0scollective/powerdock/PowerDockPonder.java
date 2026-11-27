package org.cha0scollective.powerdock;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = PowerDock.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
final class PowerDockPonder implements PonderPlugin {
    @SubscribeEvent
    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PonderIndex.addPlugin(new PowerDockPonder()));
    }

    @Override
    public String getModId() {
        return PowerDock.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        var hanger = id("catenary_hanger");
        helper.forComponents(hanger).addStoryBoard("catenary_placement", OverheadPonders::hanger);
        helper.forComponents(hanger).addStoryBoard("catenary_placement", OverheadPonders::clippedLeads);
        helper.forComponents(hanger, CatenaryWireUse.COPPER_WIRE)
                .addStoryBoard("catenary", OverheadPonders::route);
        helper.forComponents(hanger, CatenaryWireUse.COPPER_WIRE)
                .addStoryBoard("catenary_network", CatenaryNetworkPonders::branches);
        helper.forComponents(hanger)
                .addStoryBoard("catenary_network", CatenaryNetworkPonders::feeders);
        helper.forComponents(id("pantograph")).addStoryBoard("pantograph", OverheadPonders::pantograph);
        helper.forComponents(id("power_dock")).addStoryBoard("power_dock", DockingPonders::dock);
        helper.forComponents(id("rail_jumper")).addStoryBoard("rail_jumper", DockingPonders::deckJumper);
        helper.forComponents(id("flush_rail_jumper")).addStoryBoard("flush_rail_jumper", DockingPonders::flushJumper);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(PowerDock.ID, path);
    }
}
