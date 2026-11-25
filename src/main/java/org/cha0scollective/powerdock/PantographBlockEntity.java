package org.cha0scollective.powerdock;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;

import javax.annotation.Nullable;
import java.util.Objects;

public final class PantographBlockEntity extends ElectricBlockEntity {
    private static final int RECEIVER_INTERVAL = 4;
    @Nullable private BlockPos feeder;
    @Nullable private CatenaryContacts contacts;
    @Nullable private Couple<Frequency> receiver;
    @Nullable private Boolean receiverPowered;
    private long nextReceiverCheck;

    PantographBlockEntity(BlockPos pos, BlockState state) {
        super(PowerDock.PANTOGRAPH_ENTITY.get(), pos, state);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
    }

    public boolean connected() {
        return contacts != null;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) return;
        if (receiver != null && server.getGameTime() >= nextReceiverCheck) {
            nextReceiverCheck = server.getGameTime() + RECEIVER_INTERVAL;
            updateReceiver(server);
        }
        if (!getBlockState().getValue(CatenaryBlock.RAISED) || getElectricBehaviour().isPaused()
                || !(Sable.HELPER.getContaining(this) instanceof ServerSubLevel body) || body.isRemoved()) {
            disconnect();
            return;
        }
        var base = body.logicalPose().transformPosition(base());
        var contact = CatenaryLines.get(server).nearest(server, base);
        var nextFeeder = contact == null ? null : contact.feeder();
        if (contacts != null) {
            if (!Objects.equals(feeder, nextFeeder) || !contacts.live()) disconnect();
            return;
        }
        if (nextFeeder != null) {
            contacts = CatenaryContacts.close(server, worldPosition, nextFeeder, 0.04f);
            if (contacts != null) {
                feeder = nextFeeder;
                showConnection(true);
            }
        }
    }

    Vec3 base() {
        return Vec3.atLowerCornerOf(worldPosition).add(0.5, 0.3125, 0.5);
    }

    void toggleRaised() {
        setRaised(!getBlockState().getValue(CatenaryBlock.RAISED));
    }

    private void setRaised(boolean raised) {
        if (!(level instanceof ServerLevel server) || isRemoved() || isChunkUnloaded()
                || getBlockState().getValue(CatenaryBlock.RAISED) == raised) return;
        if (!raised) disconnect();
        server.setBlock(worldPosition, getBlockState().setValue(CatenaryBlock.RAISED, raised), Block.UPDATE_CLIENTS);
    }

    ItemStack receiverItem(boolean first) {
        return receiver == null ? ItemStack.EMPTY : receiver.get(first).getStack().copyWithCount(1);
    }

    void configureReceiver(ItemStack first, ItemStack second) {
        if (ItemStack.isSameItemSameComponents(receiverItem(true), first)
                && ItemStack.isSameItemSameComponents(receiverItem(false), second)) return;
        readFrequency(first, second);
        setChanged();
    }

    private void readFrequency(ItemStack first, ItemStack second) {
        receiver = first.isEmpty() && second.isEmpty() ? null
                : Couple.create(Frequency.of(first.copyWithCount(1)), Frequency.of(second.copyWithCount(1)));
        receiverPowered = null;
        nextReceiverCheck = 0;
    }

    private void updateReceiver(ServerLevel server) {
        var links = Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(server).get(receiver);
        boolean powered = false;
        if (links != null) {
            var position = worldCenter(server, worldPosition);
            double range = AllConfigs.server().logistics.linkRange.get();
            // A moving receiver can enter or leave range without the transmitter changing power.
            for (var link : links) {
                if (link.isAlive() && link.getTransmittedStrength() > 0
                        && position.distanceToSqr(worldCenter(server, link.getLocation())) < range * range) {
                    powered = true;
                    break;
                }
            }
        }
        if (receiverPowered == null || receiverPowered != powered) {
            receiverPowered = powered;
            setRaised(powered);
        }
    }

    private static Vec3 worldCenter(ServerLevel level, BlockPos pos) {
        var center = Vec3.atCenterOf(pos);
        var body = Sable.HELPER.getContaining(level, pos);
        return body == null ? center : body.logicalPose().transformPosition(center);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!clientPacket && receiver != null) {
            tag.put("ReceiverFirst", receiverItem(true).saveOptional(registries));
            tag.put("ReceiverSecond", receiverItem(false).saveOptional(registries));
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (!clientPacket) {
            readFrequency(ItemStack.parseOptional(registries, tag.getCompound("ReceiverFirst")),
                    ItemStack.parseOptional(registries, tag.getCompound("ReceiverSecond")));
        }
    }

    void disconnect() {
        if (contacts != null) contacts.open();
        feeder = null;
        contacts = null;
        showConnection(false);
    }

    private void showConnection(boolean connected) {
        if (!(level instanceof ServerLevel) || isRemoved() || isChunkUnloaded()) return;
        var state = getBlockState();
        if (state.getBlock() instanceof CatenaryBlock<?> && state.hasProperty(CatenaryBlock.CONNECTED)
                && state.getValue(CatenaryBlock.CONNECTED) != connected) {
            level.setBlock(worldPosition, state.setValue(CatenaryBlock.CONNECTED, connected), Block.UPDATE_CLIENTS);
            level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        disconnect();
    }

    @Override
    public void remove() {
        disconnect();
        super.remove();
    }
}
