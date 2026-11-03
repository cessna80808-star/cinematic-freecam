package dev.cinecam.mixin;

import dev.cinecam.render.SeeThroughPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers which extracted render-state object belongs to the local player.
 * EntityRenderState has no owner reference, so identity captured at extraction
 * is the reliable way to avoid making every player on a server see-through.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void cinecam$captureLocalPlayer(
        E entity,
        float partialTicks,
        CallbackInfoReturnable<EntityRenderState> cir
    ) {
        if (entity == Minecraft.getInstance().player) {
            SeeThroughPlayer.capture(cir.getReturnValue());
        }
    }
}
