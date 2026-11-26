package org.cha0scollective.powerdock;

import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = PowerDock.ID)
final class CatenaryWireUse {
    static final ResourceLocation COPPER_WIRE = ResourceLocation.fromNamespaceAndPath("powergrid", "wire");
    private static final ResourceLocation WIRE_CONNECTION = ResourceLocation.fromNamespaceAndPath("powergrid", "connection");
    private static final String SELECTION = "powerdock:CatenaryRoute";
    private static final int MAX_WAYPOINTS = 8;
    private static final Map<ServerPlayer, Pending> SERVER_PREVIEWS = new WeakHashMap<>();
    private static final Map<Level, ClientPreview> CLIENT_PREVIEWS = new WeakHashMap<>();
    private static final Map<Level, ClientPreview> MENU_PREVIEWS = new WeakHashMap<>();

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(ShowPreview.TYPE, ShowPreview.CODEC, (packet, context) -> {
            var level = context.player().level();
            var spans = new ArrayList<CatenarySpan>();
            for (var item : packet.geometry.getList("Runs", Tag.TAG_COMPOUND)) {
                var entry = (CompoundTag) item;
                var span = CatenarySpan.read(BlockPos.of(entry.getLong("Origin")), entry.getList("Span", Tag.TAG_COMPOUND));
                if (span != null) spans.add(span);
            }
            var preview = new ClientPreview(packet.token, List.copyOf(spans), packet.geometry.getBoolean("Conflict"));
            if (packet.geometry.getBoolean("Menu")) MENU_PREVIEWS.put(level, preview);
            else CLIENT_PREVIEWS.put(level, preview);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useCopperWire(PlayerInteractEvent.RightClickBlock event) {
        var item = event.getItemStack();
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()
                || !isLinkWire(item)
                || event.getUseBlock().isFalse() || event.getUseItem().isFalse()) return;
        var entity = event.getLevel().getBlockEntity(event.getPos());
        var hanger = entity instanceof HangerHeadBlockEntity head ? head.hanger()
                : entity instanceof CatenaryHangerBlockEntity base ? base : null;
        boolean waypoint = hanger == null && event.getLevel().getBlockState(event.getPos()).getBlock() instanceof ITrackBlock
                && activeSelection(item, event.getLevel()) != null;
        if (hanger == null && !waypoint) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getEntity() instanceof ServerPlayer player && player.level().mayInteract(player, event.getPos())) {
            if (waypoint) addWaypoint(player, event.getPos(), item);
            else if (player.level().mayInteract(player, hanger.getBlockPos())) useOnHanger(player, hanger, item);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void cancelInAir(PlayerInteractEvent.RightClickItem event) {
        var item = event.getItemStack();
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()
                || !isLinkWire(item)) return;
        var root = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!root.getCompound(SELECTION).contains("From")) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getEntity() instanceof ServerPlayer player) {
            var rail = activeSelection(item, player.level()) == null ? null : HangerTrackTarget.pick(player.serverLevel(), player);
            if (rail != null && player.level().mayInteract(player, rail.rail())) {
                addWaypoint(player, rail.rail(), item);
                return;
            }
            saveSelection(item, root, new CompoundTag());
            SERVER_PREVIEWS.remove(player);
            report(player, "selection_cleared");
        }
    }

    private static void addWaypoint(ServerPlayer player, BlockPos rail, ItemStack item) {
        var root = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var data = root.getCompound(SELECTION);
        var waypoints = data.getLongArray("Waypoints");
        if (waypoints.length >= MAX_WAYPOINTS) {
            report(player, "waypoint_limit");
            return;
        }
        if (Arrays.stream(waypoints).anyMatch(old -> old == rail.asLong())) return;
        var updated = Arrays.copyOf(waypoints, waypoints.length + 1);
        updated[waypoints.length] = rail.asLong();
        data.putLongArray("Waypoints", updated);
        clearPreview(player, data);
        data.remove("Unlink");
        saveSelection(item, root, data);
        if (data.contains("Target", Tag.TAG_LONG)
                && checkedHanger(player, BlockPos.of(data.getLong("Target"))) instanceof CatenaryHangerBlockEntity target) {
            useOnHanger(player, target, item);
        } else {
            report(player, "choose_hanger");
        }
    }

    @Nullable
    private static net.minecraft.world.level.block.entity.BlockEntity checkedHanger(ServerPlayer player, BlockPos pos) {
        return player.level().hasChunkAt(pos) && player.level().mayInteract(player, pos) ? player.level().getBlockEntity(pos) : null;
    }

    private static boolean isLinkWire(ItemStack item) {
        return COPPER_WIRE.equals(BuiltInRegistries.ITEM.getKey(item.getItem()))
                && BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(WIRE_CONNECTION).filter(item::has).isEmpty();
    }

    @Nullable static CompoundTag activeSelection(ItemStack item, Level level) {
        if (!isLinkWire(item)) return null;
        var data = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(SELECTION);
        return data.contains("From") && level.dimension().location().toString().equals(data.getString("Dimension")) ? data : null;
    }

    @Nullable static ClientPreview clientPreview(Level level, CompoundTag selection) {
        var cached = CLIENT_PREVIEWS.get(level);
        return cached != null && selection.contains("Preview", Tag.TAG_LONG) && cached.token == selection.getLong("Preview")
                ? cached : null;
    }

    @Nullable static ClientPreview menuPreview(Level level, int menuId) {
        var cached = MENU_PREVIEWS.get(level);
        return cached != null && cached.token == menuId ? cached : null;
    }

    static void showMenuPreview(ServerPlayer player, int menuId, @Nullable CatenaryLines.Link link) {
        var geometry = new CompoundTag();
        geometry.putBoolean("Menu", true);
        showPreview(player, menuId, link == null ? List.of() : List.of(link.span), geometry);
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
            SERVER_PREVIEWS.remove(player);
            if (data.getBoolean("Unlink")) {
                var lines = CatenaryLines.get(server);
                if (lines.attached(clicked).stream().anyMatch(link -> !server.mayInteract(player, link.opposite(clicked)))) {
                    saveSelection(item, root, new CompoundTag());
                    report(player, "protected_span");
                    return;
                }
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
        if (!(checkedHanger(player, start) instanceof CatenaryHangerBlockEntity first)) {
            saveSelection(item, root, new CompoundTag());
            report(player, "missing_hanger");
            return;
        }
        List<BlockPos> waypoints = Arrays.stream(data.getLongArray("Waypoints")).limit(MAX_WAYPOINTS).mapToObj(BlockPos::of).toList();
        if (waypoints.stream().anyMatch(pos -> !server.hasChunkAt(pos) || !server.mayInteract(player, pos))) {
            clearPreview(player, data);
            saveSelection(item, root, data);
            report(player, "invalid_route");
            return;
        }
        var plan = first.planLink(hit, waypoints);
        data.putLong("Target", clicked.asLong());
        if (plan.error() != null) {
            clearPreview(player, data);
            data.putBoolean("NeedsWaypoint", "choose_waypoint".equals(plan.error()));
            if (plan.route() != null) {
                data.putBoolean("Conflict", true);
                long token = server.getRandom().nextLong();
                data.putLong("Preview", token);
                var geometry = new CompoundTag();
                geometry.putBoolean("Conflict", true);
                showPreview(player, token, List.of(CatenarySpan.create(clicked, plan.route(), first.height(), hit.height())), geometry);
            }
            saveSelection(item, root, data);
            report(player, plan.error());
            return;
        }
        var pending = SERVER_PREVIEWS.get(player);
        var lines = CatenaryLines.get(server);
        if (data.contains("Confirm") && pending != null && pending.token == data.getLong("Preview")
                && pending.from.equals(start) && pending.to.equals(clicked)
                && pending.fromHeight == first.height() && pending.toHeight == hit.height()
                && pending.revision == lines.layoutRevision()
                && pending.route.trackPath().equals(plan.route().trackPath())
                && pending.route.points().equals(plan.route().points())) {
            plan.commit(pending.span);
            saveSelection(item, root, new CompoundTag());
            SERVER_PREVIEWS.remove(player);
            report(player, "linked");
            return;
        }
        data.remove("Unlink");
        data.remove("NeedsWaypoint");
        data.remove("Conflict");
        data.putLong("Confirm", clicked.asLong());
        long token = server.getRandom().nextLong();
        data.putLong("Preview", token);
        saveSelection(item, root, data);
        var newSpan = UUID.randomUUID();
        SERVER_PREVIEWS.put(player, new Pending(token, newSpan, start, clicked, first.height(), hit.height(),
                lines.layoutRevision(), plan.route()));
        showPreview(player, token, lines.preview(server, plan.span(newSpan)), new CompoundTag());
        report(player, "confirm_route");
    }

    private static void clearPreview(ServerPlayer player, CompoundTag data) {
        data.remove("Confirm");
        data.remove("Preview");
        data.remove("Conflict");
        SERVER_PREVIEWS.remove(player);
    }

    private static void showPreview(ServerPlayer player, long token, List<CatenarySpan> spans, CompoundTag geometry) {
        var runs = new ListTag();
        for (var span : spans) {
            var entry = new CompoundTag();
            entry.putLong("Origin", span.origin().asLong());
            entry.put("Span", span.write());
            runs.add(entry);
        }
        geometry.put("Runs", runs);
        PacketDistributor.sendToPlayer(player, new ShowPreview(token, geometry));
    }

    private record Pending(long token, UUID span, BlockPos from, BlockPos to, int fromHeight, int toHeight,
                           long revision, CatenaryRoute.Route route) {}

    record ClientPreview(long token, List<CatenarySpan> spans, boolean conflict) {}

    record ShowPreview(long token, CompoundTag geometry) implements CustomPacketPayload {
        static final Type<ShowPreview> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(PowerDock.ID, "catenary_preview"));
        static final StreamCodec<RegistryFriendlyByteBuf, ShowPreview> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ShowPreview::token,
                ByteBufCodecs.COMPOUND_TAG, ShowPreview::geometry, ShowPreview::new);

        @Override public Type<ShowPreview> type() { return TYPE; }
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
