package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.collections.ModdedBlocks;
import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

public final class CatenaryHangerBlockEntity extends ElectricBlockEntity implements IElectric {
    private int height = 7;
    private int width = 7;
    private int headOffset;
    @Nullable private BlockPos previous;
    @Nullable private BlockPos next;
    private boolean incomingManaged;
    private boolean outgoingManaged;
    @Nullable private CatenarySpan.Head wireHead;
    @Nullable private HangerMount mount;
    private long nextCheck;
    final ClippedFeeds clipped = new ClippedFeeds();
    final FrameWiring wiring = new FrameWiring();
    private ElectricWire positiveBridge, negativeBridge;
    private FrameStyle support = FrameStyle.FLOATING;
    private boolean legacySupportLayout;
    @Nullable private HangerFrame cachedFrame;

    CatenaryHangerBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.CATENARY_HANGER_ENTITY.get(), pos, state);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(Math.max(terminalCount(), getElectricBehaviour()==null ? 0 : getElectricBehaviour().getExternalNodes().size()));
        positiveBridge = negativeBridge = null;
        if (support != null && support.terminals() && !clipped.external) {
            positiveBridge = builder.connect(.0001f,builder.terminalNode(0),builder.terminalNode(2));
            negativeBridge = builder.connect(.0001f,builder.terminalNode(1),builder.terminalNode(3));
        }
    }

    @Override public int terminalCount() { return wiring==null ? 2 : wiring.count(support!=null && support.terminals()); }

    boolean internalBridge(Object wire) { return wire==positiveBridge || wire==negativeBridge; }

    private void growTerminals() {
        var behaviour=getElectricBehaviour();
        if(behaviour==null) return;
        var nodes=behaviour.getExternalNodes();
        // PG's full rebuild drops every wire. Append only new, independently addressable nodes.
        while(nodes.size()<terminalCount()) nodes.add(new OwnedFloatingNode(
                new BlockWireEndpoint(worldPosition,nodes.size())));
    }

    void fittingsChanged() {
        cachedFrame = null;
        growTerminals();
        wiring.rejected=-1;
        notifyUpdate();
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        if (index < 0 || index >= terminalCount()) return null;
        if (index >= 2) return wiring.terminal(frame(), index);
        if (mount == null) return CatenaryBlock.hangerPost(state.getValue(BlockStateProperties.HORIZONTAL_FACING), index);
        return headOffset == 0 ? mount.post(index) : mount.post(index).offset(0, headOffset, 0);
    }

    @Override
    public int terminalIndexAt(BlockState state, Vec3 point) {
        for (int i = 0; i < 2; i++) if (terminal(state, i).check(point)) return i;
        for (var entry : wiring.bodies(frame()).entrySet()) {
            var local = entry.getValue().local(point);
            if (Math.abs(local.x) <= 1.00001 && Math.abs(local.y) <= 1.00001
                    && local.z >= -.00001 && local.z <= 1.00001) return entry.getKey();
        }
        return -1;
    }

    @Nullable HangerMount mount() { return mount; }
    public BlockPos headPosition() { return worldPosition.above(headOffset); }
    boolean hasRaisedHead() { return headOffset != 0; }

    @Override
    public void initialize() {
        super.initialize();
        FrameInteraction.track(this,true);
        if (level instanceof ServerLevel server) {
            synchronizeLinks(CatenaryLines.get(server));
            if (mount != null) {
                int previousOffset = headOffset;
                if (moveHead(server, offsetAtHeight(height), null)) sendData();
                if (headOffset == previousOffset) refreshPowerConnections(server);
            }
        }
        alignOnPlacement();
    }

    void alignOnPlacement() {
        if (mount == null && level instanceof ServerLevel server && Sable.HELPER.getContaining(this) == null) {
            var head = CatenaryRoute.placementHead(server, worldPosition);
            if (head != null) align(server, head, null);
        }
    }

    void placeOnRail(HangerTrackTarget target, Player player) {
        if (mount != null || !(level instanceof ServerLevel server) || Sable.HELPER.getContaining(this) != null) return;
        height = HangerTrackTarget.INITIAL_HEIGHT;
        align(server, target.frame(), player);
    }

    private void align(ServerLevel server, CatenarySpan.Head head, @Nullable Player player) {
        if (mount != null) return;
        mount = HangerMount.fromTrack(worldPosition, head, getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING));
        cachedFrame = null;
        if (!moveHead(server, offsetAtHeight(height), player)) {
            mount = null;
            return;
        }
        server.setBlock(worldPosition, getBlockState().setValue(CatenaryBlock.TRACK_ALIGNED, true), Block.UPDATE_CLIENTS);
        refreshPowerConnections(server);
        notifyUpdate();
    }

    private int offsetAtHeight(int clearance) {
        return HangerFixture.headY(mount.rail.y + clearance);
    }

    private boolean moveHead(ServerLevel server, int offset, @Nullable Player player) {
        var target = worldPosition.above(offset);
        var old = headPosition();
        if (player != null && (!server.mayInteract(player, old) || !server.mayInteract(player, target))) return false;
        if (offset != 0 && !ownsHead(server, target)) {
            if (server.isOutsideBuildHeight(target) || !server.getBlockState(target).isAir()
                    || !server.setBlock(target, PowerDock.HANGER_HEAD.get().stateForOffset(offset), Block.UPDATE_ALL)) return false;
        }
        if (headOffset != offset) {
            int previousOffset = headOffset;
            headOffset = offset;
            if (previousOffset != 0 && ownsHead(server, old)) server.removeBlock(old, false);
            refreshPowerConnections(server);
            setChanged();
        }
        return true;
    }

    private boolean ownsHead(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(PowerDock.HANGER_HEAD.get()) && worldPosition.equals(HangerHeadBlock.anchor(pos, state));
    }

    private void refreshPowerConnections(ServerLevel server) {
        ElectricBlock.refreshConnectionEntities(server, worldPosition);
        for (var wires : getElectricBehaviour().getConnections().values()) {
            for (var wire : wires) if (wire instanceof CordEntity cord) cord.refreshTerminalPositions();
        }
    }

    FrameStyle support() { return support; }

    HangerFrame frame() {
        if (cachedFrame == null && fixtureHead() != null) cachedFrame = layout(fixture(), support);
        return cachedFrame;
    }

    private HangerFrame layout(HangerFixture fixture, FrameStyle style) {
        return layout(fixture,style,clipped.mask());
    }

    void clipStyle(FrameStyle next) {
        support=next;
        fittingsChanged();
    }

    HangerFrame layout(HangerFixture fixture, FrameStyle style, int mask) {
        return layout(fixture, style, mask, legacySupportLayout);
    }

    private HangerFrame layout(HangerFixture fixture, FrameStyle style, int mask, boolean legacyLayout) {
        var across = mount == null ? fixture.contactSpacing() : new Vec3(Math.cos(mount.yaw), 0, -Math.sin(mount.yaw));
        Vec3[] ends = null;
        if (mask != 0) {
            ends = new Vec3[2];
            for (int i=0;i<2;i++) ends[i] = mount == null ? CatenaryBlock.hangerPost(getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING), i).getOrigin()
                    : mount.post(i).getOrigin().add(fixture.housing().subtract(mount.housing));
        }
        var gridRail = mount == null || legacyLayout ? null : mount.gridRail();
        return new HangerFrame(fixture, style, across, wiring.fittings().values(), mask, ends, gridRail);
    }

    boolean setSupport(FrameStyle nextStyle, Player player) {
        var current=fixture();
        if (current==null || !(level instanceof ServerLevel server)) return false;
        boolean keepLegacyLayout = legacySupportLayout && support.kind() == nextStyle.kind();
        var proposed = layout(current, nextStyle, clipped.mask(), keepLegacyLayout);
        if (!clipped.fits(this,proposed,player) || !wiring.validate(this,proposed,Vec3.ZERO,player)) return false;
        boolean circuitChanged=support.terminals()!=nextStyle.terminals();
        support = nextStyle;
        legacySupportLayout = keepLegacyLayout;
        if (!support.terminals()) clipped.external=false;
        cachedFrame = proposed;
        growTerminals();
        if(circuitChanged) getElectricBehaviour().rebuildCircuit(false);
        refreshPowerConnections(server);
        setChanged();
        notifyUpdate();
        return true;
    }

    public int height() { return height; }
    public int width() { return width; }
    boolean hasLinks() {
        return previous != null || next != null
                || level instanceof ServerLevel server && !CatenaryLines.get(server).attached(worldPosition).isEmpty();
    }

    public boolean linkedTo(BlockPos neighbour) {
        return level instanceof ServerLevel server
                && CatenaryLines.get(server).attached(worldPosition).stream()
                        .anyMatch(link -> link.opposite(worldPosition).equals(neighbour));
    }

    public List<Vec3> overheadWireEnds() {
        return level instanceof ServerLevel server ? CatenaryLines.get(server).wireEnds(worldPosition) : List.of();
    }

    public boolean adjust(boolean adjustHeight) {
        int updatedHeight = adjustHeight ? height == 12 ? 4 : height + 1 : height;
        int updatedWidth = adjustHeight ? width : width == 13 ? 3 : width + 2;
        return setClearance(updatedHeight, updatedWidth, null);
    }

    boolean setClearance(int updatedHeight, int updatedWidth, @Nullable Player player) {
        if(level instanceof ServerLevel server && fixture()!=null) {
            var old=fixture();
            int delta=updatedHeight-height;
            var shift=new Vec3(0,mount==null ? 0 : offsetAtHeight(updatedHeight)-headOffset,0);
            var proposed=layout(new HangerFixture(old.wireCenter().add(0,delta,0),old.contactSpacing(),
                    old.housing().add(shift),old.railY(),updatedWidth),support);
            if(!clipped.fits(this,proposed,player) || !wiring.validate(this,proposed,shift,player)) return false;
        }
        if (updatedHeight != height && level instanceof ServerLevel server) {
            if (mount != null && !moveHead(server, offsetAtHeight(updatedHeight), player)) return false;
            CatenaryLines.get(server).resize(server, worldPosition, updatedHeight);
        }
        height = updatedHeight;
        width = updatedWidth;
        cachedFrame = null;
        nextCheck = 0;
        if(level instanceof ServerLevel server) {
            wireHead=CatenaryLines.get(server).hangerHead(worldPosition);
            refreshPowerConnections(server);
        }
        notifyUpdate();
        return true;
    }

    @Nullable
    public HangerFixture fixture() {
        var head = fixtureHead();
        if (head == null) return null;
        var origin = Vec3.atLowerCornerOf(worldPosition);
        var wires = head.center().subtract(origin);
        var housing = mount == null ? new Vec3(0.5, 0, 0.5) : mount.housing.add(0, headOffset, 0);
        var railY = mount == null ? wires.y - height : mount.rail.y;
        return new HangerFixture(wires, head.across(), housing, railY, width);
    }

    @Nullable private CatenarySpan.Head fixtureHead() {
        if (wireHead != null) return wireHead;
        return mount == null ? null : mount.wireHead(worldPosition, height);
    }

    LinkPlan planLink(CatenaryHangerBlockEntity other) {
        return planLink(other, List.of());
    }

    LinkPlan planLink(CatenaryHangerBlockEntity other, List<BlockPos> waypoints) {
        if (!(level instanceof ServerLevel server) || level != other.level || other == this
                || Sable.HELPER.getContaining(this) != null || Sable.HELPER.getContaining(other) != null) {
            return LinkPlan.rejected("invalid_route");
        }
        var lines = CatenaryLines.get(server);
        var choice = CatenaryRoute.choose(server, worldPosition, other.worldPosition, waypoints);
        var path = choice.route();
        if (path == null) return LinkPlan.rejected(choice.needsWaypoint() ? "choose_waypoint" : "invalid_route");
        for (var existing : lines.attached(worldPosition)) {
            if (!existing.opposite(worldPosition).equals(other.worldPosition)) continue;
            var old = existing.route(server);
            if (old != null && old.sameTrack(path)) return LinkPlan.rejected("duplicate_span");
        }
        if (lines.overlapsOtherLine(server, worldPosition, other.worldPosition, height, other.height, path)) {
            return new LinkPlan(this, other, path, "overlap_span");
        }
        return new LinkPlan(this, other, path, null);
    }

    record LinkPlan(@Nullable CatenaryHangerBlockEntity source, @Nullable CatenaryHangerBlockEntity destination,
                    @Nullable CatenaryRoute.Route route, @Nullable String error) {
        static LinkPlan rejected(String reason) { return new LinkPlan(null, null, null, reason); }

        CatenaryLayout.Span span(UUID id) {
            return new CatenaryLayout.Span(id, source.getBlockPos(), destination.getBlockPos(),
                    source.height, destination.height, route);
        }

        void commit(UUID id) {
            source.linkTo(destination, span(id));
        }
    }

    public boolean linkTo(CatenaryHangerBlockEntity other) {
        var plan = planLink(other);
        if (plan.error != null) return false;
        plan.commit(UUID.randomUUID());
        return true;
    }

    private void linkTo(CatenaryHangerBlockEntity other, CatenaryLayout.Span span) {
        var server = (ServerLevel) level;
        var lines = CatenaryLines.get(server);
        var path = span.route();
        align(server, CatenarySpan.Head.along(path.points().getFirst(), path.direction(true)), null);
        other.align(server, CatenarySpan.Head.along(path.points().getLast(), path.direction(false)), null);
        lines.addSpan(span);
        synchronizeLinks(lines);
        other.synchronizeLinks(lines);
        other.nextCheck = 0;
        notifyUpdate();
        other.notifyUpdate();
    }

    void unlink() {
        if (!(level instanceof ServerLevel server)) return;
        var lines = CatenaryLines.get(server);
        if (previous != null && !incomingManaged) lines.rememberLegacyCut(previous, worldPosition);
        if (next != null && !outgoingManaged) lines.rememberLegacyCut(worldPosition, next);
        var links = List.copyOf(lines.attached(worldPosition));
        lines.unlink(worldPosition);
        for (var link : links) {
            var neighbour = hangerAt(link.opposite(worldPosition));
            if (neighbour != null) neighbour.synchronizeLinks(lines);
        }
        previous = next = null;
        lines.detachPost(worldPosition);
        wireHead = null;
        notifyUpdate();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) return;
        clipped.verifyAfterLoad(server,this);
        var lines = CatenaryLines.get(server);
        synchronizeLinks(lines);
        if (previous != null && !incomingManaged && server.getGameTime() >= nextCheck) migrateLegacySpan(server, lines);
        var current = lines.hangerHead(worldPosition);
        if (!Objects.equals(wireHead, current)) {
            wireHead = current;
            cachedFrame = null;
            sendData();
        }
        lines.updatePost(worldPosition);
    }

    private void migrateLegacySpan(ServerLevel level, CatenaryLines lines) {
        nextCheck = level.getGameTime() + 20;
        var before = CatenaryLines.loadedHanger(level, previous);
        if (before == null) return;
        before.synchronizeLinks(lines);
        if (lines.legacyCut(previous, worldPosition) || !worldPosition.equals(before.next)) {
            previous = null;
            setChanged();
            return;
        }
        var fresh = CatenaryRoute.betweenHangers(level, previous, worldPosition);
        if (fresh == null) {
            nextCheck = level.getGameTime() + 100;
            return;
        }
        lines.importLegacy(previous, worldPosition, before.height, height, fresh);
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
        if (!after.isEmpty() || outgoingManaged) {
            outgoingManaged = true;
            next = after.isEmpty() ? null : after.getFirst().to;
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

    @Override
    public void onChunkUnloaded() {
        FrameInteraction.track(this,false);
        if (level instanceof ServerLevel server) CatenaryLines.get(server).detachPost(worldPosition);
        super.onChunkUnloaded();
        wireHead = null;
    }

    @Override
    public void remove() {
        FrameInteraction.track(this,false);
        if (level instanceof ServerLevel server) {
            clipped.broken(server);
            if (headOffset != 0 && ownsHead(server, headPosition())) server.removeBlock(headPosition(), false);
            if (!wiring.fittings().isEmpty()) Block.popResource(server,headPosition(),
                    new ItemStack(ModdedBlocks.WIRE_CONNECTOR.get(), wiring.fittings().size()));
            unlink();
        }
        else wireHead = null;
        super.remove();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Height", height);
        tag.putInt("Width", width);
        support.write(tag);
        tag.putBoolean("LegacySupportLayout", legacySupportLayout);
        wiring.write(tag);
        clipped.write(tag);
        if (headOffset != 0) tag.putInt("HeadOffset", headOffset);
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
        if (clientPacket && wireHead != null) tag.put("WireHead", wireHead.write(Vec3.atLowerCornerOf(worldPosition)));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        var oldSupport=support;
        var oldMount=mount;
        var oldHead=wireHead;
        int oldHeight=height, oldWidth=width, oldOffset=headOffset;
        boolean oldBridge=support.terminals() && !clipped.external;
        boolean oldLegacyLayout = legacySupportLayout;
        support = FrameStyle.read(tag);
        if (clipped.read(tag)) cachedFrame=null;
        if (wiring.read(tag)) cachedFrame = null;
        // Saved feed posts and tube connectors may already be wired at the old positions.
        legacySupportLayout = tag.contains("LegacySupportLayout")
                ? tag.getBoolean("LegacySupportLayout") : support.terminals() || !wiring.fittings().isEmpty();
        growTerminals();
        super.read(tag, registries, clientPacket);
        if(oldBridge!=(support.terminals() && !clipped.external) && getElectricBehaviour()!=null)
            getElectricBehaviour().rebuildCircuit(false);
        height = tag.contains("Height") ? Math.clamp(tag.getInt("Height"), 4, 12) : 7;
        width = tag.contains("Width") ? Math.clamp(tag.getInt("Width"), 3, 13) : 7;
        headOffset = Math.clamp(tag.getInt("HeadOffset"), -16, 16);
        previous = tag.contains("Previous") ? BlockPos.of(tag.getLong("Previous")) : null;
        next = tag.contains("Next") ? BlockPos.of(tag.getLong("Next")) : null;
        incomingManaged = tag.getBoolean("IncomingManaged");
        outgoingManaged = tag.getBoolean("OutgoingManaged");
        var placement = tag.getCompound("Mount");
        var rail = new Vec3(placement.getDouble("X"), placement.getDouble("Y"), placement.getDouble("Z"));
        double yaw = placement.getDouble("Yaw");
        if (placement.isEmpty()) mount = null;
        else if (mount == null || !mount.rail.equals(rail) || mount.yaw != yaw) mount = new HangerMount(rail, yaw);
        if (clientPacket) wireHead = tag.contains("WireHead", Tag.TAG_COMPOUND)
                ? CatenarySpan.Head.read(Vec3.atLowerCornerOf(worldPosition), tag.getCompound("WireHead")) : null;
        if (!oldSupport.equals(support) || oldMount!=mount || oldHeight!=height || oldWidth!=width
                || oldOffset!=headOffset || oldLegacyLayout != legacySupportLayout
                || !Objects.equals(oldHead,wireHead)) cachedFrame=null;
    }

}
