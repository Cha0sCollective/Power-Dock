package org.cha0scollective.powerdock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;

import java.util.Arrays;
import java.util.UUID;

final class ClippedFeeds {
    private static final ResourceLocation ITEM = ResourceLocation.parse("powergrid:insulated_copper_wire");
    private static final ResourceLocation CONNECTION = ResourceLocation.parse("powergrid:connection");
    private record Run(UUID id, int count) {}
    private final Run[] runs = new Run[2];
    boolean external;
    private boolean verifyOnLoad;

    static boolean loose(ItemStack stack) {
        return stack.is(BuiltInRegistries.ITEM.get(ITEM))
                && BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(CONNECTION).filter(stack::has).isEmpty();
    }
    int mask() { return (runs[0] == null ? 0 : 1) | (runs[1] == null ? 0 : 2); }
    int next() { return runs[0] == null ? 0 : runs[1] == null ? 1 : -1; }
    boolean ownsWire(ServerLevel level,Object wire) {
        for (var run : runs) if (run != null && level.getEntity(run.id) instanceof ClippedLeadEntity lead && lead.getWire()==wire) return true;
        return false;
    }
    boolean owns(UUID id) {
        for (var run : runs) if (run != null && run.id.equals(id)) return true;
        return false;
    }
    boolean forget(UUID id) {
        for (int i=0;i<2;i++) if (runs[i]!=null && runs[i].id.equals(id)) {
            runs[i]=null;
            return true;
        }
        return false;
    }
    private int cost(ServerLevel level, ClippedRoute route) {
        var type = WireRegistry.forItem(level, BuiltInRegistries.ITEM.get(ITEM));
        return type == null ? Integer.MAX_VALUE : Math.max(1,(int)Math.ceil(type.itemsPerMeter()*route.length()));
    }
    private static InteractionResult message(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key,args), true);
        return InteractionResult.CONSUME;
    }
    InteractionResult install(ServerLevel level, Player player, CatenaryHangerBlockEntity hanger, ItemStack stack) {
        int pin = next();
        if (pin < 0) return message(player,"gui.powerdock.clip_full");
        var before = hanger.support();
        if (!ClippedRoute.supported(before)) return InteractionResult.PASS;
        if (before.terminals() && !external) return message(player,"gui.powerdock.clip_integrated");
        var style = before.terminals() ? before : before.change(14);
        var proposed = hanger.layout(hanger.fixture(),style,mask() | (1<<pin));
        var route = proposed.leads[pin];
        if (route == null || !hanger.wiring.validate(hanger,proposed,Vec3.ZERO,player)
                || !clear(level,player,hanger,proposed,route))
            return message(player,"message.powerdock.hanger_blocked");
        int needed = cost(level,route);
        if (needed == Integer.MAX_VALUE || !player.isCreative() && needed>stack.getCount())
            return message(player,"gui.powerdock.clip_need",needed);
        var wire = new ClippedLeadEntity(PowerDock.CLIPPED_LEAD.get(),level);
        var pos = hanger.getBlockPos();
        boolean oldExternal = external;
        external = true;
        runs[pin] = new Run(wire.getUUID(),needed);
        hanger.clipStyle(style);
        wire.setPos(Vec3.atCenterOf(pos));
        wire.setItem(stack.getItem(),needed);
        wire.setColor(ClippedLeadEntity.JACKET);
        wire.setEndpoint1(new BlockWireEndpoint(pos,2+pin));
        wire.setEndpoint2(new BlockWireEndpoint(pos,pin));
        if (wire.isRemoved() || wire.getWire() == null || !level.addFreshEntity(wire)) {
            wire.discard();
            runs[pin] = null;
            external = oldExternal;
            hanger.clipStyle(before);
            return InteractionResult.FAIL;
        }
        if (!player.isCreative()) stack.shrink(needed);
        return message(player,pin==0?"gui.powerdock.clip_added_positive":"gui.powerdock.clip_added_negative",needed);
    }
    private boolean clear(ServerLevel level,Player player,CatenaryHangerBlockEntity hanger,HangerFrame frame,ClippedRoute route) {
        for (var part : route.parts()) {
            if (!hanger.wiring.placed(level,player,hanger,part)) return false;
            for (var fitting : hanger.wiring.fittings().values()) {
                var body = fitting.body(frame);
                if (body != null && FrameWiring.overlap(part,body)) return false;
            }
        }
        return true;
    }
    boolean fits(CatenaryHangerBlockEntity hanger,HangerFrame frame,Player player) {
        var level=(ServerLevel)hanger.getLevel();
        for (int i=0;i<2;i++) if (runs[i]!=null) {
            var old=hanger.frame().leads[i];
            var next=frame.leads[i];
            if (next == null || cost(level,next)>runs[i].count || !next.equals(old)
                    && (!clear(level,player,hanger,frame,next) || !clear(level,player,hanger,hanger.frame(),old))) {
                hanger.wiring.rejected=2+i;
                return false;
            }
        }
        return true;
    }
    InteractionResult cut(ServerLevel level,Player player,CatenaryHangerBlockEntity hanger,int pin,InteractionHand hand) {
        var run=runs[pin];
        if (run==null || !(level.getEntity(run.id) instanceof ClippedLeadEntity entity)) return InteractionResult.PASS;
        if (!clear(level,player,hanger,hanger.frame(),hanger.frame().leads[pin])) return message(player,"message.powerdock.hanger_blocked");
        if (FrameInteraction.wireCutters(player.getItemInHand(hand))) return entity.interact(player,hand);
        entity.discard();
        if (!player.isCreative()) player.getInventory().placeItemBackInInventory(new ItemStack(BuiltInRegistries.ITEM.get(ITEM),run.count));
        return InteractionResult.CONSUME;
    }
    void verifyAfterLoad(ServerLevel level,CatenaryHangerBlockEntity hanger) {
        if (!verifyOnLoad || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(hanger.getBlockPos()))
                || !level.isPositionEntityTicking(hanger.getBlockPos())) return;
        verifyOnLoad = false;
        boolean changed = false;
        for (int i=0;i<runs.length;i++) if (runs[i]!=null && !(level.getEntity(runs[i].id) instanceof ClippedLeadEntity)) {
            runs[i] = null;
            changed = true;
        }
        if (changed) hanger.fittingsChanged();
    }
    void broken(ServerLevel level) {
        for (var run:runs) if (run!=null && level.getEntity(run.id) instanceof ClippedLeadEntity wire && !wire.isRemoved()) wire.kill();
    }
    void write(CompoundTag tag) {
        if (!external) return;
        tag.putBoolean("ClippedFeed",true);
        var list=new ListTag();
        for (int i=0;i<2;i++) if (runs[i]!=null) {
            var entry=new CompoundTag();
            entry.putInt("Pin",i);
            entry.putUUID("Wire",runs[i].id);
            entry.putInt("Count",runs[i].count);
            list.add(entry);
        }
        tag.put("ClippedFeedWires",list);
    }
    boolean read(CompoundTag tag) {
        boolean enabled=tag.getBoolean("ClippedFeed");
        if (!enabled && !external) return false;
        Run[] loaded=new Run[2];
        if (enabled) for (var raw:tag.getList("ClippedFeedWires",Tag.TAG_COMPOUND)) {
            var entry=(CompoundTag)raw;
            int i=entry.getInt("Pin"), n=entry.getInt("Count");
            if (i>=0 && i<2 && n>0 && n<=64 && entry.hasUUID("Wire")) loaded[i]=new Run(entry.getUUID("Wire"),n);
        }
        boolean changed=external!=enabled || !Arrays.equals(runs,loaded);
        external=enabled;
        if (changed) verifyOnLoad=enabled;
        System.arraycopy(loaded,0,runs,0,2);
        return changed;
    }
}
