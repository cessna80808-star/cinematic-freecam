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
 * orientation with the cinematic pose. The intro starts inside the player's
 * head and the outro returns there, so toggling never snaps.
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
        CameraPose shot = camera.pose(entity, partialTicks);
        double eased = camera.blendEased();

        CameraPose from;
        CameraPose to;
        double progress;
        if (camera.isEnabled()) {
            // Emerge from the player's eyes out to the shot.
            from = camera.eyePose(entity, partialTicks);
            to = shot;
            progress = eased;
        } else {
            // Fall from the shot back to the player's eyes, or to the vanilla
            // camera when that is where we came from. After the perspective is
            // restored, blend into the actual vanilla pose during a short
            // settle so the final handoff cannot snap.
            from = shot;
            CameraPose vanilla = new CameraPose(this.position, this.yRot, this.xRot, 0.0F);
            if (camera.isPerspectiveRestored()) {
                if (camera.justRestoredPerspective() || camera.handoffPose() == null) {
                    CameraPose start = camera.returnsToFirstPerson()
                        ? camera.eyePose(entity, partialTicks)
                        : vanilla;
                    camera.captureHandoff(start);
                    to = start;
                } else {
                    to = cinecam$lerpPose(camera.handoffPose(), vanilla, camera.settleProgress());
                }
            } else {
                to = camera.returnsToFirstPerson()
                    ? camera.eyePose(entity, partialTicks)
                    : vanilla;
            }
            progress = 1.0 - eased;
        }

        Vec3 resultPosition = from.pos().lerp(to.pos(), progress);
        Quaternionf resultRotation = cinecam$quat(from).slerp(cinecam$quat(to), (float) progress);

        this.setPosition(resultPosition);
        this.setRotation(to.yaw(), to.pitch());
        this.rotation.set(resultRotation);
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
        float target = Mth.clamp((float) client.config().fov + camera.fovOffset(), 20.0F, 120.0F);
        float base = cir.getReturnValue();
        cir.setReturnValue(Mth.lerp((float) blend, base, target));
    }

    private static CameraPose cinecam$lerpPose(CameraPose from, CameraPose to, double progress) {
        float t = (float) progress;
        return new CameraPose(
            from.pos().lerp(to.pos(), progress),
            from.yaw() + Mth.wrapDegrees(to.yaw() - from.yaw()) * t,
            Mth.lerp(t, from.pitch(), to.pitch()),
            Mth.lerp(t, from.roll(), to.roll())
        );
    }

    private static Quaternionf cinecam$quat(CameraPose pose) {
        return new Quaternionf().rotationYXZ(
            (float) Math.PI - (float) Math.toRadians(pose.yaw()),
            -(float) Math.toRadians(pose.pitch()),
            (float) Math.toRadians(pose.roll())
        );
    }
}
