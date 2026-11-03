package dev.cinecam.mixin;

import dev.cinecam.render.SeeThroughPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces only the base skin render type of the local player while a Cinecam
 * shot is active. Armour and other layers keep the vanilla pass; the body is
 * enough to keep the subject readable when terrain sits between the freecam and
 * the player.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow
    public abstract Identifier getTextureLocation(LivingEntityRenderState state);

    @Inject(method = "getRenderType", at = @At("RETURN"), cancellable = true)
    private void cinecam$seeThroughLocalPlayer(
        LivingEntityRenderState state,
        boolean bodyVisible,
        boolean translucent,
        boolean glowing,
        CallbackInfoReturnable<RenderType> cir
    ) {
        if (glowing || !SeeThroughPlayer.shouldApply(state)) {
            return;
        }
        RenderType original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        cir.setReturnValue(SeeThroughPlayer.typeFor(this.getTextureLocation(state), original));
    }
}
