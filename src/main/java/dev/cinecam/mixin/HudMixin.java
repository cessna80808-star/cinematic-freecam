package dev.cinecam.mixin;

import dev.cinecam.CinecamClient;
import dev.cinecam.camera.CineCamera;
import dev.cinecam.hud.CinecamOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla HUD (and the first-person extras that read the HUD hidden
 * flag) while a shot is engaged, and draws the letterbox on top.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void cinecam$hideHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        CineCamera camera = CinecamClient.cameraInstance();
        if (camera == null || !camera.isEngaged() || !camera.config().hideHud) {
            return;
        }
        if (camera.blendEased() <= 0.001) {
            Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState.isHudHidden = false;
            return;
        }
        Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState.isHudHidden = true;
        CinecamOverlay.draw(graphics, camera);
        ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cinecam$drawOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        CineCamera camera = CinecamClient.cameraInstance();
        if (camera == null || !camera.isEngaged() || camera.config().hideHud) {
            return;
        }
        CinecamOverlay.draw(graphics, camera);
    }
}
