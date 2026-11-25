package org.cha0scollective.powerdock;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = PowerDock.ID)
final class DockSearch {
    private static final Map<ServerLevel, DockSearch> LEVELS = new IdentityHashMap<>();
    private final SpatialBuckets<ServerSubLevel> bodies = new SpatialBuckets<>();
    private long tick = Long.MIN_VALUE;

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }

    static List<ServerSubLevel> nearby(ServerLevel level, AABB bounds) {
        var index = LEVELS.computeIfAbsent(level, unused -> new DockSearch());
        if (index.tick != level.getGameTime()) index.refresh(level);
        return index.bodies.find(bounds);
    }

    private void refresh(ServerLevel level) {
        tick = level.getGameTime();
        bodies.clear();
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (var sublevel : container.getAllSubLevels()) {
            if (!(sublevel instanceof ServerSubLevel body) || body.isRemoved()) continue;
            var box = body.boundingBox();
            bodies.add(new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()), body);
        }
    }
}
