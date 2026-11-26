package org.cha0scollective.powerdock;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

final class LoconauticsCompatibility {
    private LoconauticsCompatibility() {}

    static boolean isRailCarriage(@Nullable ServerSubLevel body) {
        if (body == null || !Registry.INSTALLED) return false;
        if (Registry.lookup != null) {
            try {
                return Registry.lookup.invoke(null, body.getUniqueId()) != null;
            } catch (ReflectiveOperationException | LinkageError error) {
                Registry.lookup = null;
                LogUtils.getLogger().warn("Power Dock could not identify Loconautics carriages. Mechanical capture is disabled.", error);
            }
        }
        // A missing train lookup must never send a rail carriage through mechanical capture.
        return true;
    }

    @Nullable
    static CoupledEnd coupledEnd(@Nullable BlockEntity knuckle) {
        var api = Knuckles.methods;
        if (api == null || !api.type.isInstance(knuckle)) return null;
        try {
            if (!(boolean) api.connected.invoke(knuckle)) return null;
            var body = (UUID) api.partnerBody.invoke(knuckle);
            var pos = (BlockPos) api.partnerPos.invoke(knuckle);
            return body == null || pos == null ? null : new CoupledEnd(body, pos);
        } catch (ReflectiveOperationException | LinkageError error) {
            Knuckles.methods = null;
            LogUtils.getLogger().warn("Rail Jumper could not read the Loconautics knuckles.", error);
            return null;
        }
    }

    static boolean isKnuckle(BlockEntity entity) {
        var api = Knuckles.methods;
        return api != null && api.type.isInstance(entity);
    }

    record CoupledEnd(UUID bodyId, BlockPos knucklePos) {}

    @Nullable
    static OccupiedRails occupiedRails(ServerSubLevel body) {
        var lookup = Registry.lookup;
        var api = Rails.methods;
        if (lookup == null || api == null) return null;
        try {
            var train = lookup.invoke(null, body.getUniqueId());
            if (train == null || (boolean) api.derailed.invoke(train)) return null;
            var car = api.car.invoke(train);
            if (car == null) return null;
            var rail = api.rail.invoke(car);
            if (rail != null) return new OccupiedRails(railEdge((TravellingPoint) api.leading.invoke(rail)),
                    railEdge((TravellingPoint) api.trailing.invoke(rail)));
            var bogeys = (List<?>) api.bogeys.invoke(car);
            if (bogeys.isEmpty()) return null;
            return new OccupiedRails(bogeyEdge(api, bogeys.getFirst(), api.leading),
                    bogeyEdge(api, bogeys.getLast(), api.trailing));
        } catch (ReflectiveOperationException | LinkageError error) {
            Rails.methods = null;
            LogUtils.getLogger().warn("Power Dock could not read the occupied Loconautics track.", error);
            return null;
        }
    }

    @Nullable
    private static CatenaryRoute.RailEdge bogeyEdge(RailMethods api, Object bogey, Method point) throws ReflectiveOperationException {
        var rail = api.bogeyRail.invoke(bogey);
        return rail == null ? null : railEdge((TravellingPoint) point.invoke(rail));
    }

    @Nullable
    private static CatenaryRoute.RailEdge railEdge(@Nullable TravellingPoint point) {
        return point == null || point.edge == null ? null : CatenaryRoute.RailEdge.of(point.edge);
    }

    record OccupiedRails(@Nullable CatenaryRoute.RailEdge leading, @Nullable CatenaryRoute.RailEdge trailing) {}

    private record RailMethods(Method derailed, Method car, Method rail, Method bogeys, Method bogeyRail,
                               Method leading, Method trailing) {}

    private static final class Rails {
        @Nullable private static RailMethods methods = Registry.INSTALLED ? findMethods() : null;

        @Nullable
        private static RailMethods findMethods() {
            if (Registry.lookup == null) return null;
            try {
                var train = Registry.lookup.getReturnType();
                var car = train.getMethod("car");
                var rail = car.getReturnType().getMethod("carriage");
                var type = rail.getReturnType();
                var bogey = Class.forName(train.getName() + "$Bogey");
                return new RailMethods(train.getMethod("isDerailed"), car, rail,
                        car.getReturnType().getMethod("bogeys"), bogey.getMethod("rail"),
                        type.getMethod("leading"), type.getMethod("trailing"));
            } catch (ReflectiveOperationException | LinkageError error) {
                LogUtils.getLogger().warn("Power Dock could not find the Loconautics rail positions.", error);
                return null;
            }
        }
    }

    private record KnuckleMethods(Class<?> type, Method connected, Method partnerBody, Method partnerPos) {}

    private static final class Knuckles {
        @Nullable private static KnuckleMethods methods = Registry.INSTALLED ? findMethods() : null;

        @Nullable
        private static KnuckleMethods findMethods() {
            try {
                var type = Class.forName("com.lycoris.loconautics.content.knuckle.KnuckleBlockEntity");
                return new KnuckleMethods(type, type.getMethod("isConnected"), type.getMethod("getPartnerSubLevelId"),
                        type.getMethod("getPartnerLocalPos"));
            } catch (ReflectiveOperationException | LinkageError error) {
                LogUtils.getLogger().warn("Rail Jumper could not find the Loconautics knuckle API.", error);
                return null;
            }
        }
    }

    private static final class Registry {
        private static final boolean INSTALLED = ModList.get().isLoaded("loconautics");
        @Nullable private static Method lookup = INSTALLED ? findLookup() : null;

        @Nullable
        private static Method findLookup() {
            try {
                return Class.forName("com.lycoris.loconautics.trainphysics.state.SableTrainRegistry")
                        .getMethod("bySubLevel", UUID.class);
            } catch (ReflectiveOperationException | LinkageError error) {
                LogUtils.getLogger().warn("Power Dock could not find the Loconautics train registry. Mechanical capture is disabled.", error);
                return null;
            }
        }
    }
}
