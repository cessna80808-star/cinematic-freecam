package dev.cinecam.mixin;

import dev.cinecam.CinecamClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives manual framing first refusal on the mouse wheel. When the wheel is
 * consumed for lens zoom, vanilla does not also change the selected hotbar
 * slot. GUI screens and normal (non-manual) play are left untouched.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cinecam$manualZoom(long window, double xOffset, double yOffset, CallbackInfo ci) {
        CinecamClient client = CinecamClient.getInstance();
        if (client != null && client.handleManualScroll(yOffset)) {
            ci.cancel();
        }
    }
}
