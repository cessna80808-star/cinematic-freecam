package dev.cinecam.mixin;

import dev.cinecam.CinecamClient;
import dev.cinecam.camera.CameraPose;
import dev.cinecam.camera.CineCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs after vanilla has positioned the camera, then replaces the position and
 * orientation with the cinematic pose. Doing this before the camera state is
 * extracted means the world, fog and projection all see the same camera.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private Vec3 position;
    @Shadow
    private float xRot;
    @Shadow
    private float yRot;
    @Shadow
    @Final
    private Quaternionf rotation;
    @Shadow
    @Final
    private Vector3f forwards;
    @Shadow
    @Final
    private Vector3f up;
    @Shadow
    @Final
    private Vector3f left;
    @Shadow
    private int matrixPropertiesDirty;

    @Shadow
    public abstract Entity entity();

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "update", at = @At("TAIL"))
    private void cinecam$applyShot(DeltaTracker deltaTracker, CallbackInfo ci) {
        CinecamClient client = CinecamClient.getInstance();
        if (client == null) {
            return;
        }
        CineCamera camera = client.camera();
        if (camera == null || !camera.isEngaged()) {
            return;
        }
        camera.advance();

        Entity entity = this.entity();
        if (entity == null) {
            return;
        }

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
        CameraPose pose = camera.pose(entity, partialTicks);
        double blend = camera.blendEased();

        Vec3 vanillaPosition = this.position;
        Quaternionf targetRotation = new Quaternionf().rotationYXZ(
            (float) Math.PI - (float) Math.toRadians(pose.yaw()),
            -(float) Math.toRadians(pose.pitch()),
            (float) Math.toRadians(pose.roll())
        );
        Quaternionf blendedRotation = new Quaternionf(this.rotation).slerp(targetRotation, (float) blend);

        this.setPosition(vanillaPosition.lerp(pose.pos(), blend));
        this.setRotation(pose.yaw(), pose.pitch());
        this.rotation.set(blendedRotation);
        this.rotation.transform(new Vector3f(0.0F, 0.0F, -1.0F), this.forwards);
        this.rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F), this.up);
        this.rotation.transform(new Vector3f(-1.0F, 0.0F, 0.0F), this.left);
        this.matrixPropertiesDirty |= 3;
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void cinecam$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        CinecamClient client = CinecamClient.getInstance();
        if (client == null) {
            return;
        }
        CineCamera camera = client.camera();
        if (camera == null || !camera.isEngaged()) {
            return;
        }
        double blend = camera.blendEased();
        if (blend <= 0.0) {
            return;
        }
        float base = cir.getReturnValue();
        cir.setReturnValue(Mth.lerp((float) blend, base, (float) client.config().fov));
    }
}
