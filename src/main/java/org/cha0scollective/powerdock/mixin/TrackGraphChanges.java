package org.cha0scollective.powerdock.mixin;

import com.simibubi.create.content.trains.graph.TrackGraph;
import org.cha0scollective.powerdock.CatenaryTrackIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TrackGraph.class, remap = false)
abstract class TrackGraphChanges {
    @Inject(method = {"invalidateBounds", "addNode", "disconnectNodes"}, at = @At("RETURN"))
    private void powerdock$topologyChanged(CallbackInfo ci) {
        CatenaryTrackIndex.graphChanged((TrackGraph) (Object) this);
    }

    @Inject(method = {"addNodeIfAbsent", "putConnection"}, at = @At("RETURN"))
    private void powerdock$topologyChanged(CallbackInfoReturnable<Boolean> cir) {
        CatenaryTrackIndex.graphChanged((TrackGraph) (Object) this);
    }
}
