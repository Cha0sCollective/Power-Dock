package org.cha0scollective.powerdock.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import net.minecraft.world.level.LevelAccessor;
import org.cha0scollective.powerdock.CatenaryTrackIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import javax.annotation.Nullable;

@Mixin(value = TrackGraphHelper.class, remap = false)
abstract class TrackGraphLookup {
    @WrapOperation(method = "getGraphLocationAt",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/GlobalRailwayManager;getGraph(Lnet/minecraft/world/level/LevelAccessor;Lcom/simibubi/create/content/trains/graph/TrackNodeLocation;)Lcom/simibubi/create/content/trains/graph/TrackGraph;"))
    @Nullable
    private static TrackGraph powerdock$lookUpCatenaryNode(GlobalRailwayManager manager, LevelAccessor level,
                                                          TrackNodeLocation location, Operation<TrackGraph> original) {
        return CatenaryTrackIndex.usesIndexedNodes(level) ? CatenaryTrackIndex.indexedGraph(location)
                : original.call(manager, level, location);
    }
}
