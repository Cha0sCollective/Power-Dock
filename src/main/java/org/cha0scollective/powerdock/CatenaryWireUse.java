package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.joml.Vector3f;

@EventBusSubscriber(modid = PowerDock.ID)
final class CatenaryWireUse {
    static final ResourceLocation COPPER_WIRE = ResourceLocation.fromNamespaceAndPath("powergrid", "wire");
    private static final ResourceLocation WIRE_CONNECTION = ResourceLocation.fromNamespaceAndPath("powergrid", "connection");
    private static final String SELECTION = "powerdock:CatenaryRoute";

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useCopperWire(PlayerInteractEvent.RightClickBlock event) {
        var item = event.getItemStack();
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()
                || !COPPER_WIRE.equals(BuiltInRegistries.ITEM.getKey(item.getItem()))
                || BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(WIRE_CONNECTION).filter(item::has).isPresent()
                || event.getUseBlock().isFalse() || event.getUseItem().isFalse()
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof CatenaryHangerBlockEntity hanger)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getEntity() instanceof ServerPlayer player && player.level().mayInteract(player, event.getPos())) {
            useOnHanger(player, hanger, item);
        }
    }

    private static void useOnHanger(ServerPlayer player, CatenaryHangerBlockEntity hit, ItemStack item) {
        ServerLevel server = player.serverLevel();
        var clicked = hit.getBlockPos();
        var root = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var data = root.getCompound(SELECTION);
        var dimension = server.dimension().location().toString();
        if (!dimension.equals(data.getString("Dimension")) || !data.contains("From")) {
            data = new CompoundTag();
            data.putString("Dimension", dimension);
            data.putLong("From", clicked.asLong());
            saveSelection(item, root, data);
            report(player, "choose_hanger");
            return;
        }

        var start = BlockPos.of(data.getLong("From"));
        if (start.equals(clicked)) {
            if (data.getBoolean("Unlink")) {
                hit.unlink();
                saveSelection(item, root, new CompoundTag());
                report(player, "unlinked");
            } else if (hit.hasLinks() && !data.contains("Confirm")) {
                data.putBoolean("Unlink", true);
                saveSelection(item, root, data);
                report(player, "confirm_unlink");
            } else {
                saveSelection(item, root, new CompoundTag());
                report(player, "selection_cleared");
            }
            return;
        }
        if (!server.hasChunkAt(start) || !server.mayInteract(player, start)
                || !(server.getBlockEntity(start) instanceof CatenaryHangerBlockEntity first)) {
            saveSelection(item, root, new CompoundTag());
            report(player, "missing_hanger");
            return;
        }
        if (data.contains("Confirm") && data.getLong("Confirm") == clicked.asLong()) {
            if (first.linkTo(hit)) {
                saveSelection(item, root, new CompoundTag());
                report(player, "linked");
            } else report(player, "invalid_route");
            return;
        }
        var preview = first.preview(hit);
        if (preview == null) {
            report(player, "invalid_route");
            return;
        }
        preview(server, player, preview);
        data.remove("Unlink");
        data.putLong("Confirm", clicked.asLong());
        saveSelection(item, root, data);
        report(player, "confirm_route");
    }

    private static void preview(ServerLevel server, ServerPlayer player, CatenarySpan preview) {
        var copper = new DustParticleOptions(new Vector3f(0.95f, 0.47f, 0.24f), 1);
        var teal = new DustParticleOptions(new Vector3f(0.15f, 0.76f, 0.79f), 1);
        for (int i = 0; i < preview.points.size(); i += 2) {
            var node = preview.points.get(i);
            var supply = node.supply();
            var returnWire = node.returnWire();
            server.sendParticles(player, copper, true, supply.x, supply.y, supply.z, 1, 0, 0, 0, 0);
            server.sendParticles(player, teal, true, returnWire.x, returnWire.y, returnWire.z, 1, 0, 0, 0, 0);
        }
    }

    private static void saveSelection(ItemStack wire, CompoundTag root, CompoundTag selection) {
        if (selection.isEmpty()) root.remove(SELECTION);
        else root.put(SELECTION, selection);
        if (root.isEmpty()) wire.remove(DataComponents.CUSTOM_DATA);
        else wire.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static void report(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("message.powerdock.catenary_" + key), true);
    }
}
