package dev.cinecam.camera;

import dev.cinecam.CinecamConfig;
import dev.cinecam.Shot;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the cinematic camera state: which shot is playing, how far the intro or
 * outro blend is, how long the shot has been running, and where the camera
 * should be this frame.
 */
public final class CineCamera {
    private static final Logger LOGGER = LoggerFactory.getLogger("cinecam");
    private static final double[] SPEED_STEPS = {0.5, 0.75, 1.0, 1.5, 2.0};
    private static final String[] SPEED_LABELS = {"0.5x", "0.75x", "1x", "1.5x", "2x"};
    private static final long TOAST_MILLIS = 2600L;
    private static final double CUT_SECONDS = 0.35;

    private final CinecamConfig config;

    private boolean enabled;
    private boolean engaged;
    private boolean frozen;
    private boolean directorEnabled;
    private double directorTimer;
    private double cutFade;
    private double blend;
    private double phase;
    private double frameDelta;
    private long lastFrameNanos = -1L;
    private Vec3 anchor;
    private Vec3 staticAnchor;
    private CameraType savedCameraType;
    private int speedIndex = 2;
    private String toast = "";
    private long toastUntil;

    public CineCamera(CinecamConfig config) {
        this.config = config;
        this.directorEnabled = config.director;
    }

    public CinecamConfig config() {
        return config;
    }

    public Shot shot() {
        return config.shot;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True while the shot still influences the camera, including the outro blend. */
    public boolean isEngaged() {
        return engaged;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public boolean isDirectorEnabled() {
        return directorEnabled;
    }

    public double cutFade() {
        return cutFade;
    }

    public double blend() {
        return blend;
    }

    /** Smootherstep-eased blend, used for all visual interpolation. */
    public double blendEased() {
        double t = Mth.clamp(blend, 0.0, 1.0);
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    public double speedFactor() {
        return SPEED_STEPS[speedIndex];
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean value) {
        if (this.enabled == value) {
            return;
        }
        boolean fresh = !this.engaged;
        this.enabled = value;
        if (value && fresh) {
            this.engaged = true;
            this.blend = 0.0;
            this.phase = 0.0;
            this.lastFrameNanos = -1L;
            this.anchor = null;
            this.staticAnchor = null;
            this.directorTimer = 0.0;
            Minecraft minecraft = Minecraft.getInstance();
            this.savedCameraType = minecraft.options.getCameraType();
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        showToast(value ? config.shot.displayName() : "Off");
    }

    /**
     * Called once per client tick. Keeps the camera in third person while the
     * shot is engaged and bails out cleanly if the world disappears.
     */
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (engaged && (minecraft.player == null || minecraft.level == null)) {
            enabled = false;
            engaged = false;
            blend = 0.0;
            restoreCameraType();
            return;
        }
        if (!engaged) {
            return;
        }
        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    private void restoreCameraType() {
        if (savedCameraType != null) {
            Minecraft.getInstance().options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
    }

    public void nextShot() {
        setShot(config.shot.next());
    }

    public void setShot(Shot shot) {
        if (config.shot == shot) {
            showToast(shot.displayName());
            return;
        }
        config.shot = shot;
        config.save(LOGGER);
        directorTimer = 0.0;
        cutFade = config.cutDip ? 1.0 : 0.0;
        showToast(shot.displayName());
    }

    public void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_STEPS.length;
        showToast("Speed " + SPEED_LABELS[speedIndex]);
    }

    public void toggleFreeze() {
        frozen = !frozen;
        showToast(frozen ? "Frozen" : "Playing");
    }

    public void toggleDirector() {
        directorEnabled = !directorEnabled;
        directorTimer = 0.0;
        config.director = directorEnabled;
        config.save(LOGGER);
        showToast(directorEnabled ? "Director on" : "Director off");
    }

    public void showToast(String text) {
        this.toast = text;
        this.toastUntil = System.currentTimeMillis() + TOAST_MILLIS;
    }

    public String toastText() {
        return System.currentTimeMillis() < toastUntil ? toast : "";
    }

    public float toastAlpha() {
        long now = System.currentTimeMillis();
        long remaining = toastUntil - now;
        if (remaining <= 0L) {
            return 0.0F;
        }
        long elapsed = TOAST_MILLIS - remaining;
        float fadeIn = Math.min(1.0F, elapsed / 200.0F);
        float fadeOut = Math.min(1.0F, remaining / 400.0F);
        return Math.min(fadeIn, fadeOut);
    }

    /**
     * A pose taken from inside the player's head, used as the start of the
     * emerge animation and the end of the return animation.
     */
    public CameraPose eyePose(Entity entity, float partialTicks) {
        return new CameraPose(
            entity.getEyePosition(partialTicks),
            entity.getViewYRot(partialTicks),
            entity.getViewXRot(partialTicks),
            0.0F
        );
    }

    /**
     * True when the ending transition should finish inside the player rather
     * than at the vanilla third-person offset.
     */
    public boolean returnsToFirstPerson() {
        return savedCameraType == CameraType.FIRST_PERSON;
    }

    /**
     * Extra FOV applied by the active shot. Vertigo uses this for its dolly
     * zoom: the camera pushes in while the lens widens.
     */
    public float fovOffset() {
        if (config.shot == Shot.VERTIGO) {
            double cycle = 0.5 - 0.5 * Math.cos(phase * 0.22);
            return (float) ((cycle - 0.5) * 16.0);
        }
        return 0.0F;
    }

    /**
     * Advances shot time and the intro/outro blend. Uses wall-clock frame time
     * (clamped) so the move stays smooth and frame-rate independent.
     */
    public void advance() {
        long now = System.nanoTime();
        double delta = lastFrameNanos < 0L ? 0.0 : (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        delta = Mth.clamp(delta, 0.0, 0.1);

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isPaused()) {
            delta = 0.0;
        }
        frameDelta = delta;

        if (enabled && !frozen) {
            phase += delta * speedFactor();
        }

        if (enabled && directorEnabled && config.directorSeconds > 0.0) {
            directorTimer += delta;
            if (directorTimer >= config.directorSeconds) {
                directorTimer = 0.0;
                nextShot();
            }
        }

        if (cutFade > 0.0) {
            cutFade = Math.max(0.0, cutFade - delta / CUT_SECONDS);
        }

        double goal = enabled ? 1.0 : 0.0;
        if (blend != goal) {
            double step = config.introSeconds < 0.05 ? 1.0 : delta / config.introSeconds;
            blend = goal > blend ? Math.min(goal, blend + step) : Math.max(goal, blend - step);
        }

        if (!enabled && engaged && blend <= 0.0) {
            engaged = false;
            restoreCameraType();
        }
    }

    /**
     * Computes the shot for this frame. The anchor follows the player with a
     * small exponential lag so walking does not make the camera jitter.
     */
    public CameraPose pose(Entity cameraEntity, float partialTicks) {
        Vec3 feet = cameraEntity.getPosition(partialTicks);
        if (!config.follow) {
            if (staticAnchor == null) {
                staticAnchor = feet;
            }
            feet = staticAnchor;
        }

        if (anchor == null || anchor.distanceToSqr(feet) > 48.0 * 48.0) {
            anchor = feet;
        } else {
            double alpha = 1.0 - Math.exp(-frameDelta * config.followRate);
            anchor = anchor.lerp(feet, alpha);
        }

        Vec3 lookAt = anchor.add(0.0, config.targetHeight, 0.0);
        double t = phase;
        double radius = config.radius;
        double height = config.height;
        double theta;
        double roll;

        switch (config.shot) {
            case ORBIT -> {
                theta = t * 0.20;
                roll = 0.0;
            }
            case SPIRAL -> {
                theta = t * 0.26;
                radius *= 0.90 + 0.18 * Math.sin(t * 0.21);
                height += 2.2 + 2.0 * Math.sin(t * 0.17);
                roll = config.roll * 0.5 * Math.sin(t * 0.23);
            }
            case CRANE -> {
                theta = t * 0.16;
                double progress = 0.5 - 0.5 * Math.cos(t * 0.10);
                radius *= 1.2 - 0.3 * progress;
                height += (1.0 - progress) * 4.5;
                roll = config.roll * (0.2 - 0.7 * progress);
            }
            case DOLLY -> {
                theta = t * 0.19;
                radius += 2.5 * Math.sin(t * 0.13);
                height += 1.3 * Math.sin(t * 0.09 + 1.0);
                roll = config.roll * Math.sin(t * 0.12);
            }
            case FLYBY -> {
                double u = (t % 18.0) / 18.0;
                theta = -2.1 + u * 4.2;
                radius = 15.0 - 7.0 * Math.sin(Math.PI * u);
                height = config.height * 0.4 + 5.0 * Math.sin(Math.PI * u);
                roll = config.roll * 1.2 * Math.sin(Math.PI * u);
            }
            case FIGURE8 -> {
                theta = t * 0.18;
                height += 1.4 + 1.2 * Math.sin(theta * 0.7);
                roll = config.roll * 0.6 * Math.sin(theta);
            }
            case TOPDOWN -> {
                theta = t * 0.12;
                radius = Math.max(2.5, config.radius * 0.45);
                height = config.height + 10.0 + 1.5 * Math.sin(t * 0.10);
                roll = 0.0;
            }
            case PENDULUM -> {
                theta = 1.25 * Math.sin(t * 0.35);
                radius = config.radius * 1.1;
                height = config.height + 1.0;
                roll = config.roll * 0.8 * Math.sin(t * 0.35);
            }
            case VERTIGO -> {
                theta = t * 0.14;
                double cycle = 0.5 - 0.5 * Math.cos(t * 0.22);
                radius *= 1.35 - 0.65 * cycle;
                height += 0.4 * Math.sin(t * 0.14);
                roll = 0.0;
            }
            default -> {
                theta = 0.0;
                roll = 0.0;
            }
        }

        double x;
        double z;
        if (config.shot == Shot.FIGURE8) {
            x = Math.cos(theta) * radius;
            z = Math.sin(2.0 * theta) * radius * 0.65;
        } else {
            x = Math.cos(theta) * radius;
            z = Math.sin(theta) * radius;
        }

        double shake = config.shake;
        if (shake > 0.0) {
            x += (Math.sin(t * 2.1) + 0.5 * Math.sin(t * 5.3 + 1.7)) * shake;
            height += (Math.sin(t * 2.7 + 0.6) + 0.5 * Math.sin(t * 6.1)) * shake;
            z += (Math.cos(t * 2.4 + 0.9) + 0.5 * Math.cos(t * 5.9)) * shake;
            roll += Math.sin(t * 1.7) * shake * 8.0;
        }

        Vec3 pos = new Vec3(anchor.x + x, anchor.y + height, anchor.z + z);
        Vec3 direction = lookAt.subtract(pos);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-direction.y, horizontal));
        return new CameraPose(pos, yaw, pitch, (float) roll);
    }
}
