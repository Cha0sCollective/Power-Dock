package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
    private int height = 7;
    private int width = 7;
    @Nullable private BlockPos previous;
    @Nullable private BlockPos next;
    private boolean incomingManaged;
    private boolean outgoingManaged;
    @Nullable private BlockPos fromRail;
    @Nullable private BlockPos toRail;
    @Nullable private CatenarySpan span;
    @Nullable private BlockPos renderedFeeder;
    @Nullable private CatenaryContacts contacts;
    @Nullable private CatenaryLines.Upstream connectedTo;
    @Nullable private HangerMount mount;
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
        if (level instanceof ServerLevel server) synchronizeLinks(CatenaryLines.get(server));
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
    boolean isFeeder() { return previous == null; }

    public void adjust(boolean adjustHeight) {
        if (adjustHeight) {
            height = height == 12 ? 4 : height + 1;
            if (level instanceof ServerLevel server) CatenaryLines.get(server).resize(server, worldPosition, height);
        } else width = width == 13 ? 3 : width + 2;
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
        return path == null ? null : CatenarySpan.create(other.worldPosition, path, height, other.height);
    }

    @Nullable
    private CatenaryRoute.Route proposedRoute(CatenaryHangerBlockEntity other) {
        if (!(level instanceof ServerLevel server) || level != other.level || other == this
                || Sable.HELPER.getContaining(this) != null || Sable.HELPER.getContaining(other) != null) return null;
        var lines = CatenaryLines.get(server);
        synchronizeLinks(lines);
        other.synchronizeLinks(lines);
        if (next != null || other.hasLinks()) return null;
        return CatenaryRoute.betweenHangers(server, worldPosition, other.worldPosition);
    }

    public boolean linkTo(CatenaryHangerBlockEntity other) {
        var path = proposedRoute(other);
        if (path == null || !(level instanceof ServerLevel server)) return false;
        var lines = CatenaryLines.get(server);
        var ancestor = worldPosition;
        for (int depth = 2; ancestor != null; depth++) {
            if (depth > CatenaryLines.MAX_HANGERS || ancestor.equals(other.worldPosition)) return false;
            var incoming = lines.incoming(ancestor);
            if (incoming != null) ancestor = incoming.from;
            else {
                var hanger = CatenaryLines.loadedHanger(server, ancestor);
                if (hanger == null) return false;
                ancestor = hanger.previous;
            }
        }
        align(server, CatenarySpan.Head.along(path.points().getFirst(), path.direction(true)));
        other.align(server, CatenarySpan.Head.along(path.points().getLast(), path.direction(false)));
        lines.put(worldPosition, other.worldPosition, height, other.height, path);
        next = other.worldPosition;
        outgoingManaged = true;
        other.previous = worldPosition;
        other.incomingManaged = true;
        other.nextCheck = 0;
        notifyUpdate();
        other.notifyUpdate();
        return true;
    }

    void unlink() {
        if (level instanceof ServerLevel server) {
            var lines = CatenaryLines.get(server);
            synchronizeLinks(lines);
            lines.unlink(worldPosition);
        }
        var before = hangerAt(previous);
        var after = hangerAt(next);
        if (before != null && worldPosition.equals(before.next)) {
            before.next = null;
            before.notifyUpdate();
        }
        if (after != null && worldPosition.equals(after.previous)) {
            after.previous = null;
            after.closeContacts();
            after.updateSpan(null, null);
            after.notifyUpdate();
        }
        previous = next = null;
        closeContacts();
        updateSpan(null, null);
        notifyUpdate();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server) || previous == null) return;
        var lines = CatenaryLines.get(server);
        synchronizeLinks(lines);
        if (previous != null && server.getGameTime() >= nextCheck) refreshRoute(server, lines);
        var link = lines.incoming(worldPosition);
        var currentSpan = link != null && link.route(server) != null ? link.span : null;
        var root = currentSpan == null ? null : lines.root(server, worldPosition);
        if (span != currentSpan || !Objects.equals(renderedFeeder, root)) {
            updateSpan(currentSpan, root);
            sendData();
        }
        var upstream = available() && currentSpan != null ? lines.loadedUpstream(server, worldPosition) : null;
        if (contacts != null && (!Objects.equals(connectedTo, upstream) || !contacts.live())) closeContacts();
        if (contacts == null && upstream != null) {
            contacts = CatenaryContacts.close(server, worldPosition, upstream.pos(), 0.02f * upstream.hops());
            if (contacts != null) connectedTo = upstream;
        }
    }

    private void refreshRoute(ServerLevel level, CatenaryLines lines) {
        nextCheck = level.getGameTime() + 20;
        var before = CatenaryLines.loadedHanger(level, previous);
        if (before == null) return;
        before.synchronizeLinks(lines);
        if (!worldPosition.equals(before.next)) {
            // A beta2 neighbor was unlinked before its chunk could save the old end of the span.
            previous = null;
            setChanged();
            return;
        }
        var link = lines.incoming(worldPosition);
        var from = CatenaryRoute.trackBelow(level, previous);
        var to = CatenaryRoute.trackBelow(level, worldPosition);
        boolean changed = link == null || link.route(level) == null || !Objects.equals(from, fromRail)
                || !Objects.equals(to, toRail) || before.height != link.fromHeight || height != link.toHeight;
        if (!changed) return;
        var fresh = CatenaryRoute.betweenHangers(level, previous, worldPosition);
        if (fresh == null) {
            if (link != null) lines.suspend(link);
            nextCheck = level.getGameTime() + 100;
            return;
        }
        fromRail = from;
        toRail = to;
        lines.put(previous, worldPosition, before.height, height, fresh);
        synchronizeLinks(lines);
        before.synchronizeLinks(lines);
    }

    private void synchronizeLinks(CatenaryLines lines) {
        var before = lines.incoming(worldPosition);
        var after = lines.outgoing(worldPosition);
        var oldPrevious = previous;
        var oldNext = next;
        boolean oldIncomingManaged = incomingManaged;
        boolean oldOutgoingManaged = outgoingManaged;
        if (before != null || incomingManaged) {
            incomingManaged = true;
            previous = before == null ? null : before.from;
        }
        if (after != null || outgoingManaged) {
            outgoingManaged = true;
            next = after == null ? null : after.to;
        }
        if (!Objects.equals(previous, oldPrevious) || !Objects.equals(next, oldNext)
                || oldIncomingManaged != incomingManaged || oldOutgoingManaged != outgoingManaged) {
            setChanged();
        }
    }

    boolean available() {
        return !isRemoved() && !isChunkUnloaded() && !getElectricBehaviour().isPaused();
    }

    @Nullable
    private CatenaryHangerBlockEntity hangerAt(@Nullable BlockPos pos) {
        if (pos != null && level instanceof ServerLevel server) return CatenaryLines.loadedHanger(server, pos);
        return pos != null && level != null && level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof CatenaryHangerBlockEntity hanger && !hanger.isRemoved()
                ? hanger : null;
    }

    private void updateSpan(@Nullable CatenarySpan updated, @Nullable BlockPos feeder) {
        if (span != null && level != null && level.isClientSide) span.unregisterClient(level);
        span = updated;
        renderedFeeder = feeder;
        if (span != null && level != null && level.isClientSide) registerClientSpan();
    }

    private void registerClientSpan() {
        span.registerClient(level, renderedFeeder == null ? worldPosition : renderedFeeder);
    }

    private void closeContacts() {
        if (contacts != null) contacts.open();
        contacts = null;
        connectedTo = null;
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        closeContacts();
        updateSpan(null, null);
    }

    @Override
    public void remove() {
        if (level instanceof ServerLevel) unlink();
        else updateSpan(null, null);
        super.remove();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Height", height);
        tag.putInt("Width", width);
        if (previous != null) tag.putLong("Previous", previous.asLong());
        if (next != null) tag.putLong("Next", next.asLong());
        if (incomingManaged) tag.putBoolean("IncomingManaged", true);
        if (outgoingManaged) tag.putBoolean("OutgoingManaged", true);
        if (mount != null) {
            var placement = new CompoundTag();
            placement.putDouble("X", mount.rail.x);
            placement.putDouble("Y", mount.rail.y);
            placement.putDouble("Z", mount.rail.z);
            placement.putDouble("Yaw", mount.yaw);
            tag.put("Mount", placement);
        }
        if (clientPacket && span != null) {
            tag.put("Span", span.write());
            if (renderedFeeder != null) tag.putLong("Feeder", renderedFeeder.asLong());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        height = tag.contains("Height") ? Math.clamp(tag.getInt("Height"), 4, 12) : 7;
        width = tag.contains("Width") ? Math.clamp(tag.getInt("Width"), 3, 13) : 7;
        previous = tag.contains("Previous") ? BlockPos.of(tag.getLong("Previous")) : null;
        next = tag.contains("Next") ? BlockPos.of(tag.getLong("Next")) : null;
        incomingManaged = tag.getBoolean("IncomingManaged");
        outgoingManaged = tag.getBoolean("OutgoingManaged");
        var placement = tag.getCompound("Mount");
        var rail = new Vec3(placement.getDouble("X"), placement.getDouble("Y"), placement.getDouble("Z"));
        double yaw = placement.getDouble("Yaw");
        if (placement.isEmpty()) mount = null;
        else if (mount == null || !mount.rail.equals(rail) || mount.yaw != yaw) mount = new HangerMount(rail, yaw);
        if (clientPacket) updateSpan(CatenarySpan.read(worldPosition, tag.getList("Span", Tag.TAG_COMPOUND)),
                tag.contains("Feeder", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("Feeder")) : null);
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (span != null && level.isClientSide) registerClientSpan();
    }
}
