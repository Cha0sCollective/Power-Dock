package org.cha0scollective.powerdock;

import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

final class PonderPresentation {
    private PonderPresentation() {}

    static void open(SceneBuilder scene, String id, String title, int size, float scale) {
        scene.title(id, title);
        scene.configureBasePlate(0, 0, size);
        scene.scaleSceneView(scale);
        scene.showBasePlate();
        scene.idle(10);
    }

    static void explain(SceneBuilder scene, Vec3 focus, String text, int ticks) {
        scene.overlay().showText(ticks).text(text).pointAt(focus).placeNearTarget().attachKeyFrame();
        scene.idle(ticks + 8);
    }

    @FunctionalInterface
    interface Geometry {
        void draw(PonderLevel level, CatenaryRenderer.Beams beams);
    }

    static void geometry(SceneBuilder scene, Geometry geometry) {
        scene.debug().enqueueCallback(active -> addGeometry(active, geometry));
    }

    static void addGeometry(PonderScene scene, Geometry geometry) {
        scene.addElement(new AnimatedSceneElementBase() {
            {
                forceApplyFade(1);
            }

            @Override
            protected void renderLast(PonderLevel level, MultiBufferSource buffers, GuiGraphics graphics,
                                      float fade, float partialTick) {
                geometry.draw(level, CatenaryRenderer.beams(graphics.pose(), buffers, LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY));
            }
        });
    }
}
