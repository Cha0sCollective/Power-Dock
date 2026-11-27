package org.cha0scollective.powerdock;

import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.patryk3211.powergrid.collections.ModdedBlocks;
import org.patryk3211.powergrid.collections.ModdedTags;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWire;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = PowerDock.ID)
final class FrameInteraction {
    private static final Map<Level, Map<Long, Set<BlockPos>>> loaded = Collections.synchronizedMap(new WeakHashMap<>());
    record Hit(CatenaryHangerBlockEntity hanger, HangerFrame.Member member, Vec3 local, Vec3 point, int slot) {}

    static void track(CatenaryHangerBlockEntity hanger, boolean add) {
        var level = hanger.getLevel();
        if (level == null || hanger.isVirtual()) return;
        var pos = hanger.getBlockPos();
        var chunk = ChunkPos.asLong(pos);
        if (add) loaded.computeIfAbsent(level, key->new HashMap<>()).computeIfAbsent(chunk, key->new HashSet<>()).add(pos);
        else {
            var chunks = loaded.get(level);
            if (chunks == null || !chunks.containsKey(chunk)) return;
            var set = chunks.get(chunk);
            set.remove(pos);
            if (set.isEmpty()) chunks.remove(chunk);
        }
    }

    static final int INSTALL = -2, CUT_FIRST = -3;
    static boolean connector(ItemStack stack) { return stack.is(ModdedBlocks.WIRE_CONNECTOR.asItem()); }
    private static boolean wrench(ItemStack stack) { return stack.is(AllItems.WRENCH.get()); }
    static boolean wireCutters(ItemStack stack) { return stack.is(ModdedTags.Item.WIRE_CUTTERS.tag); }
    static Hit pick(Player player, ItemStack held) {
        if (player.isSpectator() || held.isEmpty()) return null;
        boolean cutters = wireCutters(held);
        if (!cutters && !connector(held) && !wrench(held) && !IWire.isWire(player.level(), held.getItem())) return null;
        boolean laying = player.isShiftKeyDown() && ClippedFeeds.loose(held);
        boolean cutting = cutters || player.isShiftKeyDown() && wrench(held);
        var level = player.level();
        var chunks = loaded.get(level);
        if (chunks == null) return null;
        var ray = HangerTrackTarget.trace(level, player);
        if (ray == null) return null;
        var end = ray.eye().add(ray.direction().scale(Math.min(32, ray.visible())+.00001));
        double best = ray.eye().distanceToSqr(end)+1e-7;
        Hit winner = null;
        int x1 = (int)Math.floor(Math.min(ray.eye().x, end.x)-16)>>4;
        int x2 = (int)Math.floor(Math.max(ray.eye().x, end.x)+16)>>4;
        int z1 = (int)Math.floor(Math.min(ray.eye().z, end.z)-16)>>4;
        int z2 = (int)Math.floor(Math.max(ray.eye().z, end.z)+16)>>4;
        for (int x = x1;x<=x2;x++) for (int z = z1;z<=z2;z++) {
            var candidates = chunks.get(ChunkPos.asLong(x, z));
            if (candidates == null) continue;
            for (var pos : candidates) {
                if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger)) continue;
                if (Math.abs(pos.getY()-ray.eye().y)>32) continue;
                var frame = hanger.frame();
                if (frame == null) continue;
                var offset = Vec3.atLowerCornerOf(pos);
                var a = ray.eye().subtract(offset);
                var b = end.subtract(offset);
                // Also intersect the steel when wiring. An upright can hide a connector on its far face.
                for (var part : frame.members) {
                    var hit = part.hit(a, b);
                    if (hit == null) continue;
                    var point = part.point(hit.x, hit.y, hit.z);
                    double distance = point.distanceToSqr(a);
                    if (distance>=best) continue;
                    best = distance;
                    int pin = ClippedRoute.pin(part);
                    int slot;
                    if (laying && ClippedRoute.supported(hanger.support()) && (part.tube() || pin >= 0)) slot = INSTALL;
                    else if (cutting && pin >= 0) slot = CUT_FIRST - pin;
                    else if (part.tube() && connector(held)) slot = -1;
                    else { winner = null; continue; }
                    winner = new Hit(hanger,part,hit,point,slot);
                }
                for (var entry : hanger.wiring.bodies(frame).entrySet()) {
                    var part = entry.getValue();
                    var hit = part.hit(a, b);
                    if (hit == null) continue;
                    var point = part.point(hit.x, hit.y, hit.z);
                    double distance = point.distanceToSqr(a);
                    if (distance>best+1e-7) continue;
                    best = distance;
                    winner = cutters ? null : new Hit(hanger, part, hit, point, entry.getKey());
                }
            }
        }
        return winner;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseBlock().isFalse() || event.getUseItem().isFalse()) return;
        use(event);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void useAir(PlayerInteractEvent.RightClickItem event) { use(event); }
    private static void use(PlayerInteractEvent event) {
        var player = event.getEntity();
        var held = event.getItemStack();
        var hit = pick(player, held);
        if (hit == null || !player.mayBuild()) return;
        if (connector(held) && hit.slot>=0 || wrench(held) && hit.slot>CUT_FIRST && (!player.isShiftKeyDown() || hit.slot<4)) return;
        var hanger = hit.hanger;
        var anchor = hanger.getBlockPos();
        var cell = BlockPos.containing(hit.point.add(Vec3.atLowerCornerOf(anchor)));
        if (!player.level().mayInteract(player, anchor) || !player.level().mayInteract(player, cell)) return;
        InteractionResult result = InteractionResult.sidedSuccess(player.level().isClientSide);
        if (player.level() instanceof ServerLevel level) {
            if (hit.slot == INSTALL) {
                result = hanger.clipped.install(level,player,hanger,held);
            } else if (hit.slot <= CUT_FIRST) {
                result = hanger.clipped.cut(level,player,hanger,CUT_FIRST-hit.slot,event.getHand());
            } else if (connector(held)) {
                var fitting = TubeFitting.on(hit.member, hit.local);
                var part = fitting.body(hanger.frame());
                int slot = hanger.wiring.freeSlot();
                if (slot<0 || !hanger.wiring.placed(level, player, hanger, part)
                        || hanger.frame().members.stream().anyMatch(other -> ClippedRoute.pin(other)>=0 && FrameWiring.overlap(other,part))
                        || hanger.wiring.bodies(hanger.frame()).values().stream().anyMatch(other->FrameWiring.overlap(other, part))) {
                    result = InteractionResult.FAIL;
                    player.displayClientMessage(Component.translatable("message.powerdock.hanger_blocked"), true);
                } else {
                    hanger.wiring.put(slot, fitting);
                    hanger.fittingsChanged();
                    if (!player.isCreative()) held.shrink(1);
                }
            } else if (wrench(held)) {
                var endpoint = new BlockWireEndpoint(anchor, hit.slot);
                var routes = GlobalElectricNetworks.getWorldNetworks(level).findConnectedWires(endpoint);
                if (hanger.getElectricBehaviour().getConnections().containsKey(endpoint) || routes != null && !routes.isEmpty()) {
                    player.displayClientMessage(Component.translatable("gui.powerdock.disconnect_wire"), true);
                } else if (hanger.wiring.placed(level, player, hanger, hit.member)) {
                    hanger.wiring.remove(hit.slot);
                    hanger.fittingsChanged();
                    if (!player.isCreative()) player.getInventory().placeItemBackInInventory(new ItemStack(ModdedBlocks.WIRE_CONNECTOR.get()));
                }
            } else {
                var at = hit.member.point(0, 0, .5).add(Vec3.atLowerCornerOf(anchor));
                result = hanger.onWire(hanger.getBlockState(), new UseOnContext(player, event.getHand(),
                        new BlockHitResult(at, Direction.UP, anchor, false)));
            }
        }
        if (event instanceof PlayerInteractEvent.RightClickBlock block) { block.setCancellationResult(result); block.setCanceled(true); }
        if (event instanceof PlayerInteractEvent.RightClickItem air) { air.setCancellationResult(result); air.setCanceled(true); }
    }
}
