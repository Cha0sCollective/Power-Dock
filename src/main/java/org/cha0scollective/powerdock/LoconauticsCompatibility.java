package org.cha0scollective.powerdock;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
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
