package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;

import javax.annotation.Nullable;
import java.util.Objects;

public final class CatenaryHangerBlockEntity extends ElectricBlockEntity implements IElectric {
    private static final int MAX_HANGERS = 32;
    private int height = 7;
    private int width = 7;
    @Nullable private BlockPos previous;
    @Nullable private BlockPos next;
    @Nullable private BlockPos fromRail;
    @Nullable private BlockPos toRail;
    @Nullable private CatenaryRoute.Route route;
    @Nullable private CatenarySpan span;
    @Nullable private CatenaryContacts contacts;
    @Nullable private HangerMount mount;
    private int fromHeight;
    private int toHeight;
    private long nextCheck;

    CatenaryHangerBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.CATENARY_HANGER_ENTITY.get(), pos, state);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
    }

    @Override public int terminalCount() { return 2; }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        if (index < 0 || index >= terminalCount()) return null;
        return mount == null ? CatenaryBlock.post(state.getValue(BlockStateProperties.HORIZONTAL_FACING), index)
                : mount.post(index);
    }

    @Nullable HangerMount mount() { return mount; }

    @Override
    public void initialize() {
        super.initialize();
        alignOnPlacement();
    }

    void alignOnPlacement() {
        if (mount == null && level instanceof ServerLevel server && Sable.HELPER.getContaining(this) == null) {
            var head = CatenaryRoute.placementHead(server, worldPosition);
            if (head != null) align(server, head);
        }
    }

    private void align(ServerLevel server, CatenarySpan.Head head) {
        if (mount != null) return;
        mount = HangerMount.fromTrack(worldPosition, head, getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING));
        server.setBlock(worldPosition, getBlockState().setValue(CatenaryBlock.TRACK_ALIGNED, true), Block.UPDATE_CLIENTS);
        ElectricBlock.refreshConnectionEntities(server, worldPosition);
        notifyUpdate();
    }

    public int height() { return height; }
    public int width() { return width; }
    boolean hasLinks() { return previous != null || next != null; }

    public void adjust(boolean adjustHeight) {
        if (adjustHeight) height = height == 12 ? 4 : height + 1;
        else width = width == 13 ? 3 : width + 2;
        nextCheck = 0;
        notifyUpdate();
    }

    @Nullable CatenarySpan span() { return span; }

    @Nullable CatenarySpan.Head fixtureHead() {
        if (span != null) return span.points.getLast();
        var downstream = hangerAt(next);
        if (downstream != null && downstream.span != null) return downstream.span.points.getFirst();
        return mount == null ? null : mount.wireHead(worldPosition, height);
    }

    @Nullable
    CatenarySpan preview(CatenaryHangerBlockEntity other) {
        var path = proposedRoute(other);
        return path == null ? null : CatenarySpan.create(other, path, height, other.height);
    }

    @Nullable
    private CatenaryRoute.Route proposedRoute(CatenaryHangerBlockEntity other) {
        if (!(level instanceof ServerLevel server) || level != other.level || other == this || next != null
                || other.previous != null || other.next != null
                || Sable.HELPER.getContaining(this) != null || Sable.HELPER.getContaining(other) != null) return null;
        return CatenaryRoute.betweenHangers(server, worldPosition, other.worldPosition);
    }

    public boolean linkTo(CatenaryHangerBlockEntity other) {
        var path = proposedRoute(other);
        if (path == null) return false;
        int depth = 1;
        for (var ancestor = this; ancestor != null; ancestor = ancestor.hangerAt(ancestor.previous)) {
            if (++depth > MAX_HANGERS || ancestor == other) return false;
        }
        if (level instanceof ServerLevel server) {
            align(server, CatenarySpan.Head.along(path.points().getFirst(), path.direction(true)));
            other.align(server, CatenarySpan.Head.along(path.points().getLast(), path.direction(false)));
        }
        next = other.worldPosition;
        other.previous = worldPosition;
        other.nextCheck = 0;
        notifyUpdate();
        other.notifyUpdate();
        return true;
    }

    void unlink() {
        var before = hangerAt(previous);
        var after = hangerAt(next);
        if (before != null && worldPosition.equals(before.next)) {
            before.next = null;
            before.notifyUpdate();
        }
        if (after != null && worldPosition.equals(after.previous)) {
            after.previous = null;
            after.clearSpan();
            after.notifyUpdate();
        }
        previous = next = null;
        clearSpan();
        notifyUpdate();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server) || previous == null) return;
        var before = hangerAt(previous);
        if (before == null || !worldPosition.equals(before.next) || !available() || !before.available()) {
            clearSpan();
            return;
        }
        if (contacts != null && !contacts.live()) clearSpan();
        if (server.getGameTime() < nextCheck) return;
        nextCheck = server.getGameTime() + 20;
        var from = CatenaryRoute.trackBelow(server, previous);
        var to = CatenaryRoute.trackBelow(server, worldPosition);
        boolean changed = route == null || !route.valid()
                || !Objects.equals(from, fromRail) || !Objects.equals(to, toRail)
                || before.height != fromHeight || height != toHeight;
        if (changed) {
            var fresh = CatenaryRoute.betweenHangers(server, previous, worldPosition);
            if (fresh == null) {
                clearSpan();
                nextCheck = server.getGameTime() + 100;
                return;
            }
            fromRail = from;
            toRail = to;
            route = fresh;
            fromHeight = before.height;
            toHeight = height;
            updateSpan(CatenarySpan.create(this, fresh, fromHeight, toHeight));
            sendData();
        }
        if (contacts == null) contacts = CatenaryContacts.close(server, previous, worldPosition, 0.02f);
    }

    boolean available() {
        return !isRemoved() && !isChunkUnloaded() && !getElectricBehaviour().isPaused();
    }

    @Nullable
    CatenaryHangerBlockEntity feeder() {
        var hanger = this;
        for (int depth = 0; depth < MAX_HANGERS; depth++) {
            if (hanger.isRemoved() || hanger.isChunkUnloaded()) return null;
            if (level instanceof ServerLevel && !hanger.available()) return null;
            if (hanger.previous == null) return hanger;
            if (level instanceof ServerLevel && (hanger.contacts == null || !hanger.contacts.live())) return null;
            var parent = hanger.hangerAt(hanger.previous);
            if (parent == null || !hanger.worldPosition.equals(parent.next)) return null;
            hanger = parent;
        }
        return null;
    }

    @Nullable
    private CatenaryHangerBlockEntity hangerAt(@Nullable BlockPos pos) {
        return pos != null && level != null && level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger && !hanger.isRemoved()
                ? hanger : null;
    }

    private void updateSpan(@Nullable CatenarySpan updated) {
        if (span != null && level != null) span.unregister(level);
        span = updated;
        if (span != null && level != null) span.register(level);
    }

    private void clearSpan() {
        if (contacts != null) contacts.open();
        contacts = null;
        route = null;
        if (span != null) {
            updateSpan(null);
            if (level instanceof ServerLevel && !isChunkUnloaded() && !isRemoved()) sendData();
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        clearSpan();
    }

    @Override
    public void remove() {
        if (level instanceof ServerLevel) unlink();
        else clearSpan();
        super.remove();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Height", height);
        tag.putInt("Width", width);
        if (previous != null) tag.putLong("Previous", previous.asLong());
        if (next != null) tag.putLong("Next", next.asLong());
        if (mount != null) {
            var placement = new CompoundTag();
            placement.putDouble("X", mount.rail.x);
            placement.putDouble("Y", mount.rail.y);
            placement.putDouble("Z", mount.rail.z);
            placement.putDouble("Yaw", mount.yaw);
            tag.put("Mount", placement);
        }
        if (clientPacket && span != null) tag.put("Span", span.write());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        height = tag.contains("Height") ? Math.clamp(tag.getInt("Height"), 4, 12) : 7;
        width = tag.contains("Width") ? Math.clamp(tag.getInt("Width"), 3, 13) : 7;
        previous = tag.contains("Previous") ? BlockPos.of(tag.getLong("Previous")) : null;
        next = tag.contains("Next") ? BlockPos.of(tag.getLong("Next")) : null;
        var placement = tag.getCompound("Mount");
        var rail = new Vec3(placement.getDouble("X"), placement.getDouble("Y"), placement.getDouble("Z"));
        double yaw = placement.getDouble("Yaw");
        if (placement.isEmpty()) mount = null;
        else if (mount == null || !mount.rail.equals(rail) || mount.yaw != yaw) mount = new HangerMount(rail, yaw);
        if (clientPacket) updateSpan(CatenarySpan.read(this, tag));
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (span != null) span.register(level);
    }
}
