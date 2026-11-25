package org.cha0scollective.powerdock.mixin;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackGraph;
import org.cha0scollective.powerdock.CatenaryTrackIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlobalRailwayManager.class, remap = false)
abstract class TrackGraphRegistryChanges {
    @Inject(method = {"putGraph", "removeGraph"}, at = @At("RETURN"))
    private void powerdock$graphChanged(TrackGraph graph, CallbackInfo ci) {
        if ((Object) this == Create.RAILWAYS) CatenaryTrackIndex.graphChanged(graph);
    }
}
