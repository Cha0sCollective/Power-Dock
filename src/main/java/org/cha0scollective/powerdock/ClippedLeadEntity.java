package org.cha0scollective.powerdock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.wire.WireEntity;

/** PG owns the wire's two endpoints, resistance, temperature and burnout. The hanger draws its fixed route. */
public final class ClippedLeadEntity extends WireEntity {
    static final int JACKET = 0x413c31; // PG's undyed insulated wire
    private boolean checkedOwner;

    public ClippedLeadEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override public void tick() {
        if (!checkedOwner && level() instanceof ServerLevel level) {
            if (!(level.getBlockEntity(blockPosition()) instanceof CatenaryHangerBlockEntity hanger)
                    || !hanger.clipped.owns(getUUID())) {
                discard();
                return;
            }
            checkedOwner = true;
        }
        super.tick();
    }

    @Override public void kill() {
        // PG can reject an endpoint while an install is being staged, before any inventory was charged.
        if (isAddedToLevel()) super.kill();
        else discard();
    }

    @Override public void remove(RemovalReason reason) {
        super.remove(reason);
        if (reason.shouldDestroy() && level() instanceof ServerLevel level
                && level.getBlockEntity(blockPosition()) instanceof CatenaryHangerBlockEntity hanger
                && hanger.clipped.forget(getUUID())) hanger.fittingsChanged();
    }
}
