package dev.cinecam.camera;

import dev.cinecam.CinecamConfig;
import dev.cinecam.Shot;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the cinematic camera state: which shot is playing, how far the intro or
 * outro blend is, how long the shot has been running, and where the camera
 * should be this frame. Also adapts the Dynamic shot and keeps every shot out
 * of geometry.
 */
public final class CineCamera {
    private static final Logger LOGGER = LoggerFactory.getLogger("cinecam");
    private static final double[] SPEED_STEPS = {0.5, 0.75, 1.0, 1.5, 2.0};
    private static final String[] SPEED_LABELS = {"0.5x", "0.75x", "1x", "1.5x", "2x"};
    private static final long TOAST_MILLIS = 2600L;
    private static final double CUT_SECONDS = 0.35;
    private static final double MAX_COMBAT_DISTANCE = 18.0;
    private static final double SETTLE_SECONDS = 0.18;
    private static final double RIG_SEARCH_INTERVAL = 0.18;
    private static final double RIG_RECENTER_INTERVAL = 0.12;
    private static final double RIG_RECENTER_RATE = 1.6;
    private static final double CLAMP_OUT_RATE = 4.0;
    private static final double TELEPORT_MIN_DISTANCE = 2.5;
    private static final double TELEPORT_MIN_SPEED = 30.0;
    private static final double TELEPORT_MEMORY_SECONDS = 1.6;
    private static final double MANUAL_PAN_LIMIT = 4.0;
    private static final double MANUAL_PAN_SPEED = 3.2;
    private static final double MANUAL_PAN_EASE = 9.0;
    private static final double MANUAL_ZOOM_LIMIT = 28.0;
    private static final double MANUAL_ZOOM_STEP = 4.0;
    private static final double MANUAL_ZOOM_EASE = 12.0;
    private static final double[] RIG_ANGLE_OFFSETS = {
        0.0, 0.393, -0.393, 0.785, -0.785, 1.178, -1.178, 1.571,
        -1.571, 1.963, -1.963, 2.356, -2.356, 2.749, -2.749, Math.PI
    };
    private static final double[] RIG_HEIGHT_OFFSETS = {
        0.0, 0.8, -0.8, 1.6, -1.6, 2.4, 3.2
    };
    private static final double[] RIG_DISTANCE_FACTORS = {
        1.0, 0.86, 0.72, 0.58, 0.44, 0.30
    };

    private final CinecamConfig config;

    private boolean enabled;
    private boolean engaged;
    private boolean frozen;
    private boolean dynamicEnabled;
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
    private boolean savedFirstPerson;
    private boolean perspectiveRestored;
    private boolean justRestoredPerspective;
    private double settleTimer;
    private CameraPose handoffPose;
    private int speedIndex = 2;
    private String toast = "";
    private long toastUntil;

    private double miningTimer;
    private double combatTimer;
    private double miningWeight;
    private double combatWeight;

    private double rigAngle;
    private double rigHorizontal;
    private double rigVertical;
    private boolean rigReady;
    private double rigAngleOffset;
    private double rigHeightOffset;
    private double rigDistanceFactor = 1.0;
    private double rigSearchTimer;
    private Vec3 clampOrigin;
    private Vec3 clampDirection;
    private double clampDistance = -1.0;

    // Manual framing is deliberately separate from the shot state. It layers
    // a small aim offset and lens zoom over whichever shot is playing.
    private boolean manualEnabled;
    private double manualForwardAxis;
    private double manualRightAxis;
    private double manualPanForward;
    private double manualPanRight;
    private double manualPanForwardTarget;
    private double manualPanRightTarget;
    private double manualZoom;
    private double manualZoomTarget;

    // Dynamic framing state. Keeping these out of pose() lets the adaptive
    // camera react at tick rate while the render pose stays smooth.
    private double smoothedSpeed;
    private Vec3 smoothedVelocity = new Vec3(0.0, 0.0, 0.0);
    private double framingYaw;
    private double dynamicFov;
    private int combatTargetId = -1;
    private double retargetTimer;
    private Vec3 combatPosition;
    private Vec3 combatFocus;

    // Teleport pursuit state. AOTV-style jumps arrive as huge position deltas
    // with little or no ordinary velocity, so they are tracked separately from
    // the smoothed walking/running speed.
    private Vec3 observedPosition;
    private Vec3 teleportDirection = Vec3.ZERO;
    private double teleportEnergy;
    private double teleportJumpDistance;
    private boolean teleportSnap;

    public CineCamera(CinecamConfig config) {
        this.config = config;
        this.dynamicEnabled = config.dynamic;
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

    public boolean isDynamicEnabled() {
        return dynamicEnabled;
    }

    public boolean isManualEnabled() {
        return manualEnabled;
    }

    public void toggleManual() {
        setManualEnabled(!manualEnabled);
    }

    public void setManualEnabled(boolean value) {
        if (this.manualEnabled == value) {
            return;
        }
        this.manualEnabled = value;
        this.manualForwardAxis = 0.0;
        this.manualRightAxis = 0.0;
        if (!value) {
            this.manualPanForwardTarget = 0.0;
            this.manualPanRightTarget = 0.0;
            this.manualZoomTarget = 0.0;
            if (!this.enabled) {
                this.manualPanForward = 0.0;
                this.manualPanRight = 0.0;
                this.manualZoom = 0.0;
            }
        }
        showToast(value ? "Manual pan on" : "Manual pan off");
    }

    /**
     * Sets the raw WASD axes. The camera advances the pan from these axes in
     * pose/advance time, so input polling cannot change the framing twice.
     */
    public void setManualAxes(double forward, double right) {
        if (!this.manualEnabled) {
            this.manualForwardAxis = 0.0;
            this.manualRightAxis = 0.0;
            return;
        }
        this.manualForwardAxis = Mth.clamp(forward, -1.0, 1.0);
        this.manualRightAxis = Mth.clamp(right, -1.0, 1.0);
    }

    /** Scroll up narrows the lens; the caller consumes the wheel event. */
    public void adjustManualZoom(double amount) {
        if (!this.manualEnabled || amount == 0.0) {
            return;
        }
        this.manualZoomTarget = Mth.clamp(
            this.manualZoomTarget - amount * MANUAL_ZOOM_STEP,
            -MANUAL_ZOOM_LIMIT,
            MANUAL_ZOOM_LIMIT
        );
    }

    public void resetManualOffset() {
        this.manualForwardAxis = 0.0;
        this.manualRightAxis = 0.0;
        this.manualPanForward = 0.0;
        this.manualPanRight = 0.0;
        this.manualPanForwardTarget = 0.0;
        this.manualPanRightTarget = 0.0;
        this.manualZoom = 0.0;
        this.manualZoomTarget = 0.0;
        showToast("Manual offset centered");
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
        if (value && !fresh && perspectiveRestored) {
            Minecraft minecraft = Minecraft.getInstance();
            this.savedCameraType = minecraft.options.getCameraType();
            this.savedFirstPerson = savedCameraType == CameraType.FIRST_PERSON;
            this.perspectiveRestored = false;
            this.settleTimer = 0.0;
            this.handoffPose = null;
            resetRig();
            resetTeleportTracking();
        }
        if (value && fresh) {
            this.engaged = true;
            this.blend = 0.0;
            this.phase = 0.0;
            this.lastFrameNanos = -1L;
            this.anchor = null;
            this.staticAnchor = null;
            this.directorTimer = 0.0;
            this.miningTimer = 0.0;
            this.combatTimer = 0.0;
            this.miningWeight = 0.0;
            this.combatWeight = 0.0;
            this.smoothedSpeed = 0.0;
            this.smoothedVelocity = new Vec3(0.0, 0.0, 0.0);
            this.dynamicFov = 0.0;
            this.combatTargetId = -1;
            this.retargetTimer = 0.0;
            this.combatPosition = null;
            this.combatFocus = null;
            this.perspectiveRestored = false;
            this.justRestoredPerspective = false;
            this.settleTimer = 0.0;
            this.handoffPose = null;
            resetRig();
            resetTeleportTracking();
            Minecraft minecraft = Minecraft.getInstance();
            this.framingYaw = minecraft.player == null ? 0.0 : minecraft.player.getViewYRot(1.0F);
            this.savedCameraType = minecraft.options.getCameraType();
            this.savedFirstPerson = savedCameraType == CameraType.FIRST_PERSON;
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (!value) {
            this.perspectiveRestored = false;
            this.justRestoredPerspective = false;
            this.settleTimer = 0.0;
            this.handoffPose = null;
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
        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON && !perspectiveRestored) {
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
        resetRig();
        cutFade = config.cutDip ? 1.0 : 0.0;
        showToast(shot.displayName());
    }

    /**
     * Re-applies the mode flags after the JSON config has been reloaded. The
     * config fields alone are not enough: the camera keeps its own toggles so
     * keybind changes and reloads cannot silently disagree.
     */
    public void reloadFromConfig() {
        this.dynamicEnabled = config.dynamic;
        this.directorEnabled = config.director;
        this.directorTimer = 0.0;
        resetRig();
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

    public void toggleDynamic() {
        setDynamic(!dynamicEnabled);
    }

    public void setDynamic(boolean value) {
        if (dynamicEnabled == value) {
            return;
        }
        dynamicEnabled = value;
        config.dynamic = value;
        config.save(LOGGER);
        if (value) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                framingYaw = minecraft.player.getViewYRot(1.0F);
            }
        } else {
            retargetTimer = 0.0;
        }
        showToast(value ? "Dynamic on" : "Classic mode");
    }

    public void toggleLetterbox() {
        config.letterbox = !config.letterbox;
        config.save(LOGGER);
        showToast(config.letterbox ? "Letterbox on" : "Full frame");
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
        return savedFirstPerson;
    }

    public boolean isPerspectiveRestored() {
        return perspectiveRestored;
    }

    public boolean justRestoredPerspective() {
        return justRestoredPerspective;
    }

    public double settleProgress() {
        return settleTimer <= 0.0 ? 1.0 : 1.0 - settleTimer / SETTLE_SECONDS;
    }

    public void captureHandoff(CameraPose pose) {
        this.handoffPose = pose;
    }

    public CameraPose handoffPose() {
        return handoffPose;
    }

    /**
     * Extra FOV applied by the active shot. Dynamic opens the lens slightly at
     * speed and in combat; Vertigo uses it for its dolly zoom.
     */
    public float fovOffset() {
        double shotOffset = 0.0;
        if (config.shot == Shot.DYNAMIC) {
            shotOffset = dynamicFov;
        } else if (config.shot == Shot.VERTIGO) {
            double cycle = 0.5 - 0.5 * Math.cos(phase * 0.22);
            shotOffset = (cycle - 0.5) * 16.0;
        }
        double total = config.fov + shotOffset + manualZoom;
        return (float) (Mth.clamp(total, 20.0, 120.0) - config.fov);
    }

    public float effectiveFov() {
        return Mth.clamp((float) config.fov + fovOffset(), 20.0F, 120.0F);
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
        // Keep the shot frozen while paused, but let the emerge/return blend
        // and the cut fade finish. A camera toggle must be visible even when
        // an input-owning client has the mouse ungrabbed behind a pause screen.
        double transitionDelta = delta;
        if (minecraft.isPaused()) {
            delta = 0.0;
        }
        frameDelta = delta;
        justRestoredPerspective = false;
        updateManual(delta);

        if (enabled && !frozen) {
            phase += delta * speedFactor();
        }

        if (!frozen) {
            updateDynamic(delta);
        }

        if (enabled && directorEnabled && !frozen && config.directorSeconds > 0.0) {
            directorTimer += delta;
            if (directorTimer >= config.directorSeconds) {
                directorTimer = 0.0;
                nextShot();
            }
        }

        if (cutFade > 0.0) {
            cutFade = Math.max(0.0, cutFade - transitionDelta / CUT_SECONDS);
        }

        double goal = enabled ? 1.0 : 0.0;
        if (blend != goal) {
            double step = config.introSeconds < 0.05 ? 1.0 : transitionDelta / config.introSeconds;
            blend = goal > blend ? Math.min(goal, blend + step) : Math.max(goal, blend - step);
        }

        if (!enabled && engaged && blend <= 0.0) {
            if (!perspectiveRestored) {
                restoreCameraType();
                perspectiveRestored = true;
                justRestoredPerspective = true;
                settleTimer = SETTLE_SECONDS;
            }
            if (settleTimer > 0.0) {
                settleTimer = Math.max(0.0, settleTimer - transitionDelta);
            }
            if (settleTimer <= 0.0) {
                engaged = false;
            }
        }
    }

    /**
     * Watches what the player is doing and eases the Dynamic shot towards the
     * right framing. Mining gets a shoulder view, combat locks onto the enemy,
     * and speed opens the orbit up instead of letting the player drift away.
     */
    private void updateDynamic(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            double speed = horizontalSpeed(minecraft.player);
            Vec3 flatVelocity = horizontalVelocity(minecraft.player);
            // Track the player's real speed even in classic mode. The
            // high-speed stabilizer uses this for every shot.
            double speedTarget = speed;
            Vec3 velocityTarget = flatVelocity;
            double motionEase = 1.0 - Math.exp(-delta * 3.5);
            smoothedSpeed += (speedTarget - smoothedSpeed) * motionEase;
            smoothedVelocity = smoothedVelocity.lerp(velocityTarget, motionEase);

            double targetYaw = minecraft.player.getViewYRot(1.0F);
            double yawRate = 4.0 + 8.0 * speedEase();
            framingYaw += Mth.wrapDegrees(targetYaw - framingYaw) * (1.0 - Math.exp(-delta * yawRate));
        }

        if (minecraft.player != null && dynamicEnabled) {
            boolean attacking = minecraft.player.swinging || minecraft.player.swingTime > 0;
            boolean entityTarget = attacking
                && minecraft.hitResult != null
                && minecraft.hitResult.getType() == HitResult.Type.ENTITY
                && isValidTarget(minecraft.crosshairPickEntity, minecraft.player);
            boolean combat = minecraft.player.hurtTime > 0 || entityTarget;
            boolean mining = attacking && minecraft.hitResult instanceof BlockHitResult;

            if (combat) {
                combatTimer = 2.8;
                miningTimer = 0.0;
                retargetTimer -= delta;
                if (retargetTimer <= 0.0 || combatTarget(minecraft) == null) {
                    Entity target = findCombatTarget(minecraft, minecraft.player.hurtTime > 0);
                    combatTargetId = target == null ? -1 : target.getId();
                    retargetTimer = 0.55;
                }
            } else if (mining) {
                miningTimer = 1.8;
            }
        }

        if (!dynamicEnabled) {
            miningTimer = 0.0;
            combatTimer = 0.0;
            combatTargetId = -1;
            retargetTimer = 0.0;
        }

        if (combatTimer <= 0.0) {
            combatTargetId = -1;
        }

        miningTimer = Math.max(0.0, miningTimer - delta);
        combatTimer = Math.max(0.0, combatTimer - delta);

        double combatTarget = combatTimer > 0.0 ? 1.0 : 0.0;
        double miningTarget = combatTimer > 0.0 ? 0.0 : (miningTimer > 0.0 ? 1.0 : 0.0);
        double miningEase = 1.0 - Math.exp(-delta * (miningTarget > miningWeight ? 3.2 : 2.0));
        double combatEase = 1.0 - Math.exp(-delta * (combatTarget > combatWeight ? 3.0 : 1.8));
        miningWeight += (miningTarget - miningWeight) * miningEase;
        combatWeight += (combatTarget - combatWeight) * combatEase;

        double targetFov = dynamicEnabled
            ? Mth.clamp(3.0 * speedEase() + 2.2 * combatWeight - 1.2 * miningWeight, -1.5, 5.0)
            : 0.0;
        dynamicFov += (targetFov - dynamicFov) * (1.0 - Math.exp(-delta * 2.5));
    }

    /**
     * Eases the manual aim offset and lens zoom. The pan target is integrated
     * from the held axes, while the current offset follows it smoothly; the
     * target physics are untouched by render frequency because advance() owns
     * the frame delta.
     */
    private void updateManual(double delta) {
        if (!manualEnabled) {
            manualForwardAxis = 0.0;
            manualRightAxis = 0.0;
            manualPanForwardTarget = 0.0;
            manualPanRightTarget = 0.0;
            manualZoomTarget = 0.0;
        } else if (enabled) {
            manualPanForwardTarget = Mth.clamp(
                manualPanForwardTarget + manualForwardAxis * MANUAL_PAN_SPEED * delta,
                -MANUAL_PAN_LIMIT,
                MANUAL_PAN_LIMIT
            );
            manualPanRightTarget = Mth.clamp(
                manualPanRightTarget + manualRightAxis * MANUAL_PAN_SPEED * delta,
                -MANUAL_PAN_LIMIT,
                MANUAL_PAN_LIMIT
            );
        }

        if (delta <= 0.0) {
            return;
        }
        double panEase = 1.0 - Math.exp(-delta * MANUAL_PAN_EASE);
        double zoomEase = 1.0 - Math.exp(-delta * MANUAL_ZOOM_EASE);
        manualPanForward += (manualPanForwardTarget - manualPanForward) * panEase;
        manualPanRight += (manualPanRightTarget - manualPanRight) * panEase;
        manualZoom += (manualZoomTarget - manualZoom) * zoomEase;
        if (Math.abs(manualPanForward - manualPanForwardTarget) < 0.0005) {
            manualPanForward = manualPanForwardTarget;
        }
        if (Math.abs(manualPanRight - manualPanRightTarget) < 0.0005) {
            manualPanRight = manualPanRightTarget;
        }
        if (Math.abs(manualZoom - manualZoomTarget) < 0.001) {
            manualZoom = manualZoomTarget;
        }
    }

    /**
     * Shifts the aim point across the camera plane. The camera position and
     * collision rig are unchanged, so a manual pan can never push the lens
     * into a wall.
     */
    private Vec3 applyManualPan(Vec3 pos, Vec3 focus) {
        if (Math.abs(manualPanForward) < 0.0001 && Math.abs(manualPanRight) < 0.0001) {
            return focus;
        }
        Vec3 forward = new Vec3(focus.x - pos.x, 0.0, focus.z - pos.z);
        double forwardDistance = forward.length();
        if (forwardDistance < 1.0E-3) {
            forward = forwardFromYaw(framingYaw);
            forwardDistance = 1.0;
        }
        forward = forward.scale(1.0 / forwardDistance);
        // Keep a close shot from panning the aim through the lens and flipping
        // the view 180 degrees. Sideways pan is still free.
        double appliedForward = Mth.clamp(
            manualPanForward,
            -forwardDistance * 0.65,
            forwardDistance * 0.90
        );
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        return focus.add(right.scale(manualPanRight)).add(forward.scale(appliedForward));
    }

    /**
     * Computes the shot for this frame. The anchor follows the player with a
     * capped lag: it is smooth at walking pace but never trails far enough at
     * 400%+ speed to let the player leave the frame.
     */
    public CameraPose pose(Entity cameraEntity, float partialTicks) {
        Vec3 feet = cameraEntity.getPosition(partialTicks);
        updateTeleportTracking(cameraEntity.position());
        if (!config.follow) {
            if (staticAnchor == null) {
                staticAnchor = feet;
            }
            feet = staticAnchor;
        }

        if (anchor == null || teleportSnap || anchor.distanceToSqr(feet) > 48.0 * 48.0) {
            // Use the raw position for a teleport snap. The partial-tick feet
            // can still be interpolating across the jump, which is exactly the
            // lag this is meant to remove. A non-following shot must stay
            // anchored even when the player teleports.
            anchor = config.follow && teleportSnap ? cameraEntity.position() : feet;
        } else if (config.follow) {
            double followSpeed = horizontalSpeed(cameraEntity);
            double rate = Math.max(config.followRate, followSpeed / 0.35);
            // A teleport must not leave the orbit centre behind. The tracking
            // pulse keeps the catch-up rate high after the jump as well, so a
            // chain of AOTV jumps does not turn into a trail of stale anchors.
            if (teleportEnergy > 0.0) {
                rate = Math.max(rate, 18.0 + 72.0 * teleportEnergy);
            }
            double alpha = 1.0 - Math.exp(-frameDelta * rate);
            anchor = anchor.lerp(feet, alpha);
        }
        teleportSnap = false;

        Vec3 baseFocus = anchor.add(0.0, config.targetHeight, 0.0);
        double t = phase;
        double radius = config.radius;
        double height = config.height;
        double theta;
        double roll;

        switch (config.shot) {
            case DYNAMIC -> {
                double speed = dynamicEnabled && config.follow ? speedEase() : 0.0;
                theta = t * 0.14;
                radius = config.radius + 2.2 * speed + 0.12 * Math.sin(t * 0.11);
                height = config.height + 0.55 * speed + 0.22 * Math.sin(t * 0.29);
                roll = config.roll * 0.22 * Math.sin(t * 0.21);
            }
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
                // A full turn keeps the flyby loop continuous instead of
                // cutting from one side of the player to the other.
                theta = -2.1 + u * Math.PI * 2.0;
                radius = 15.0 - 7.0 * Math.sin(Math.PI * u);
                height = config.height * 0.4 + 5.0 * Math.sin(Math.PI * u);
                roll = config.roll * 1.2 * Math.sin(Math.PI * u);
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

        double motionStability = config.follow
            ? Mth.clamp(Math.max(speedEase(), teleportEnergy * 0.85), 0.0, 1.0)
            : 0.0;

        double x = Math.cos(theta) * radius;
        double z = Math.sin(theta) * radius;

        double shake = config.shake * (1.0 - 0.85 * motionStability);
        if (shake > 0.0) {
            x += (Math.sin(t * 2.1) + 0.5 * Math.sin(t * 5.3 + 1.7)) * shake;
            height += (Math.sin(t * 2.7 + 0.6) + 0.5 * Math.sin(t * 6.1)) * shake;
            z += (Math.cos(t * 2.4 + 0.9) + 0.5 * Math.cos(t * 5.9)) * shake;
            roll += Math.sin(t * 1.7) * shake * 8.0;
        }

        Vec3 pos = new Vec3(anchor.x + x, anchor.y + height, anchor.z + z);
        Vec3 lookAt = baseFocus;
        if (config.shot == Shot.DYNAMIC) {
            Framing framing = dynamicFraming(partialTicks, anchor, pos, baseFocus);
            pos = framing.position();
            lookAt = framing.focus();
            roll += combatWeight * 0.55 * Math.sin(phase * 0.45);
        }
        // Dynamic already has a dedicated high-speed chase. Every other shot,
        // including classic Dynamic, eases into the same stable rear chase so
        // a fast player never slides out of an orbit that is still swinging.
        if (config.follow
            && (config.shot != Shot.DYNAMIC || !dynamicEnabled)
            && motionStability > 0.001) {
            Framing stabilized = stableChase(anchor, pos, lookAt, motionStability);
            pos = stabilized.position();
            lookAt = stabilized.focus();
        }
        roll *= 1.0 - 0.7 * motionStability;
        pos = resolveGeometry(cameraEntity, lookAt, pos);
        lookAt = applyManualPan(pos, lookAt);

        Vec3 direction = lookAt.subtract(pos);
        if (direction.lengthSqr() < 1.0E-6) {
            direction = new Vec3(0.0, 0.0, 1.0);
        }
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-direction.y, horizontal));
        return new CameraPose(pos, yaw, pitch, (float) roll);
    }

    /**
     * Blends the Dynamic shot's base orbit, mining shoulder framing and combat
     * framing. Positions and focus points are both blended so the shot keeps a
     * deliberate composition instead of always pointing at the player's chest.
     */
    private Framing dynamicFraming(float partialTicks, Vec3 anchor, Vec3 basePosition, Vec3 baseFocus) {
        Vec3 position = basePosition;
        Vec3 focus = baseFocus.add(0.0, 0.35, 0.0);

        if (dynamicEnabled && config.follow) {
            double speed = speedEase();
            Vec3 lead = smoothedVelocity.scale(0.05 + 0.025 * speed);
            double leadLength = lead.length();
            if (leadLength > 1.6) {
                lead = lead.scale(1.6 / leadLength);
            }
            focus = focus.add(lead);

            // At SkyBlock speeds an orbit swings the lens into walls and lets
            // the player slide around the frame. As speed builds, settle into
            // a stable chase view behind the look direction instead.
            if (speed > 0.001) {
                Vec3 forward = forwardFromYaw(framingYaw);
                Vec3 side = rightFromForward(forward);
                Vec3 chasePosition = anchor.add(0.0, 2.55 + 0.35 * speed, 0.0)
                    .subtract(forward.scale(5.2 + 2.2 * speed))
                    .add(side.scale(0.45));
                Vec3 chaseFocus = anchor.add(0.0, 1.45, 0.0)
                    .add(forward.scale(0.95 + 1.0 * speed));
                position = position.lerp(chasePosition, speed);
                focus = focus.lerp(chaseFocus, speed);
            }
        }

        if (miningWeight > 0.001) {
            Framing mining = miningFraming(anchor);
            position = position.lerp(mining.position(), miningWeight);
            focus = focus.lerp(mining.focus(), miningWeight);
        }

        if (combatWeight > 0.001) {
            Framing combat = combatFraming(partialTicks, anchor);
            position = position.lerp(combat.position(), combatWeight);
            focus = focus.lerp(combat.focus(), combatWeight);
        }

        // A teleport is not ordinary fast movement: the player can cross the
        // whole frame between two render frames. Keep a pursuit chase active
        // for a moment after the jump, using the actual jump direction rather
        // than the view direction, so the next jump is already framed.
        if (config.follow && teleportEnergy > 0.01) {
            Framing pursuit = teleportPursuit(anchor, speedEase());
            double weight = Mth.clamp(teleportEnergy * 1.7, 0.0, 1.0);
            position = position.lerp(pursuit.position(), weight);
            focus = focus.lerp(pursuit.focus(), weight);
        }

        return new Framing(position, focus);
    }

    private Framing teleportPursuit(Vec3 anchor, double speed) {
        Vec3 forward = teleportDirection.lengthSqr() > 1.0E-6
            ? teleportDirection
            : forwardFromYaw(framingYaw);
        Vec3 side = rightFromForward(forward);
        double jumpBoost = Math.min(4.5, teleportJumpDistance * 0.28);
        double distance = 5.2 + 2.2 * speed + jumpBoost;
        Vec3 position = anchor.add(0.0, 2.55 + 0.35 * speed, 0.0)
            .subtract(forward.scale(distance))
            .add(side.scale(0.45));
        double lead = 0.95 + 1.0 * speed + Math.min(3.5, teleportJumpDistance * 0.18);
        Vec3 focus = anchor.add(0.0, 1.45, 0.0).add(forward.scale(lead));
        return new Framing(position, focus);
    }

    /**
     * A calm rear chase used by every non-Dynamic shot once movement gets
     * fast, and by all shots during a teleport. The shot keeps its own motion
     * at normal speed; at speed it gives way to a predictable over-the-shoulder
     * composition instead of swinging through nearby walls.
     */
    private Framing stableChase(Vec3 anchor, Vec3 position, Vec3 focus, double stability) {
        double speed = speedEase();
        double jump = Mth.clamp(teleportEnergy, 0.0, 1.0);
        Vec3 forward = jump > 0.05 && teleportDirection.lengthSqr() > 1.0E-6
            ? teleportDirection
            : forwardFromYaw(framingYaw);
        Vec3 side = rightFromForward(forward);
        double jumpBoost = Math.min(4.5, teleportJumpDistance * 0.28) * jump;
        double distance = 5.2 + 2.2 * speed + jumpBoost;
        Vec3 chasePosition = anchor.add(0.0, 2.35 + 0.4 * speed, 0.0)
            .subtract(forward.scale(distance))
            .add(side.scale(0.45));
        double lead = 0.95 + 1.0 * speed
            + Math.min(3.5, teleportJumpDistance * 0.18) * jump;
        Vec3 chaseFocus = anchor.add(0.0, 1.35, 0.0).add(forward.scale(lead));
        double weight = Mth.clamp(stability * 1.1, 0.0, 0.92);
        return new Framing(
            position.lerp(chasePosition, weight),
            focus.lerp(chaseFocus, weight)
        );
    }

    private Framing miningFraming(Vec3 anchor) {
        Vec3 forward = forwardFromYaw(framingYaw);
        Vec3 side = rightFromForward(forward);
        Vec3 position = anchor.add(0.0, 1.72, 0.0)
            .subtract(forward.scale(3.9))
            .add(side.scale(0.85));
        Vec3 focus = anchor.add(0.0, 1.35, 0.0)
            .add(forward.scale(0.25))
            .subtract(side.scale(0.80));
        return new Framing(position, focus);
    }

    private Framing combatFraming(float partialTicks, Vec3 anchor) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity target = combatTarget(minecraft);
        Vec3 desiredPosition;
        Vec3 desiredFocus;

        if (target != null) {
            Vec3 targetChest = target.getEyePosition(partialTicks).add(0.0, -0.35, 0.0);
            Vec3 playerChest = anchor.add(0.0, 1.15, 0.0);
            double separationX = playerChest.x - targetChest.x;
            double separationZ = playerChest.z - targetChest.z;
            double separation = Math.sqrt(separationX * separationX + separationZ * separationZ);
            Vec3 away = separation > 1.0E-3
                ? new Vec3(separationX / separation, 0.0, separationZ / separation)
                : forwardFromYaw(framingYaw).scale(-1.0);
            Vec3 side = rightFromForward(away);
            double distance = Mth.clamp(6.2 + separation * 0.25, 6.2, 9.0);

            desiredPosition = playerChest.add(away.scale(distance))
                .add(side.scale(1.45))
                .add(0.0, 1.50, 0.0);
            desiredFocus = playerChest.lerp(targetChest, 0.70).add(0.0, 0.25, 0.0);
        } else {
            Vec3 forward = forwardFromYaw(framingYaw);
            Vec3 side = rightFromForward(forward);
            double distance = Mth.clamp(config.radius, 6.0, 9.0);
            desiredPosition = anchor.add(0.0, 2.90, 0.0)
                .subtract(forward.scale(distance))
                .add(side.scale(0.90));
            desiredFocus = anchor.add(0.0, 1.45, 0.0).add(forward.scale(0.40));
        }

        if (combatPosition == null || combatFocus == null) {
            combatPosition = desiredPosition;
            combatFocus = desiredFocus;
        } else {
            double alpha = 1.0 - Math.exp(-frameDelta * 5.0);
            combatPosition = combatPosition.lerp(desiredPosition, alpha);
            combatFocus = combatFocus.lerp(desiredFocus, alpha);
        }
        return new Framing(combatPosition, combatFocus);
    }

    private Entity combatTarget(Minecraft minecraft) {
        if (combatTargetId == -1 || minecraft.player == null || minecraft.level == null) {
            return null;
        }
        for (Entity candidate : minecraft.level.getEntities(
            minecraft.player,
            minecraft.player.getBoundingBox().inflate(MAX_COMBAT_DISTANCE)
        )) {
            if (candidate.getId() == combatTargetId && isValidTarget(candidate, minecraft.player)) {
                return candidate;
            }
        }
        return null;
    }

    private Entity findCombatTarget(Minecraft minecraft, boolean allowBehind) {
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }

        Entity crosshair = minecraft.crosshairPickEntity;
        if (isValidTarget(crosshair, minecraft.player)) {
            return crosshair;
        }

        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 look = minecraft.player.getViewVector(1.0F);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity candidate : minecraft.level.getEntities(
            minecraft.player,
            minecraft.player.getBoundingBox().inflate(12.0)
        )) {
            if (!isValidTarget(candidate, minecraft.player)) {
                continue;
            }
            Vec3 toCandidate = candidate.getEyePosition().subtract(eye);
            double distance = toCandidate.length();
            if (distance < 0.05 || distance > 12.0) {
                continue;
            }
            double alignment = toCandidate.scale(1.0 / distance).dot(look);
            if (!allowBehind && alignment < 0.15) {
                continue;
            }
            double score = distance - Math.max(0.0, alignment) * 4.0;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isValidTarget(Entity entity, Entity player) {
        if (entity == null || entity == player) {
            return false;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (entity instanceof ArmorStand) {
            return false;
        }
        return entity.distanceToSqr(player) <= MAX_COMBAT_DISTANCE * MAX_COMBAT_DISTANCE;
    }

    private double speedEase() {
        double speed = Mth.clamp((smoothedSpeed - 4.8) / 13.0, 0.0, 1.0);
        return speed * speed * (3.0 - 2.0 * speed);
    }

    private static double horizontalSpeed(Entity entity) {
        Vec3 movement = entity.getDeltaMovement();
        return Math.sqrt(movement.x * movement.x + movement.z * movement.z);
    }

    private static Vec3 horizontalVelocity(Entity entity) {
        Vec3 movement = entity.getDeltaMovement();
        Vec3 flat = new Vec3(movement.x, 0.0, movement.z);
        double length = flat.length();
        return length > 24.0 ? flat.scale(24.0 / length) : flat;
    }

    private static Vec3 forwardFromYaw(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    private static Vec3 rightFromForward(Vec3 forward) {
        return new Vec3(forward.z, 0.0, -forward.x);
    }

    /**
     * Detects a teleport from the raw local-player position, remembers its
     * direction and distance, and keeps pursuit mode alive briefly. Treating a
     * jump as an isolated correction makes the camera chase the old position;
     * treating a recent jump as a direction of travel anticipates the next one.
     */
    private void updateTeleportTracking(Vec3 currentPosition) {
        if (observedPosition == null) {
            observedPosition = currentPosition;
            return;
        }
        if (frameDelta <= 1.0E-5) {
            observedPosition = currentPosition;
            return;
        }
        Vec3 step = currentPosition.subtract(observedPosition);
        observedPosition = currentPosition;
        double horizontal = step.horizontalDistance();
        double speed = horizontal / frameDelta;
        if (horizontal >= TELEPORT_MIN_DISTANCE && speed >= TELEPORT_MIN_SPEED) {
            Vec3 direction = step.horizontal();
            if (direction.lengthSqr() > 1.0E-6) {
                direction = direction.normalize();
                if (teleportEnergy > 0.05 && teleportDirection.lengthSqr() > 1.0E-6) {
                    teleportDirection = teleportDirection.scale(0.35).add(direction.scale(0.65)).normalize();
                } else {
                    teleportDirection = direction;
                }
            }
            teleportJumpDistance = teleportEnergy > 0.05
                ? Mth.lerp(0.45, teleportJumpDistance, horizontal)
                : horizontal;
            teleportEnergy = 1.0;
            teleportSnap = true;
            // Rebuild the rig at the new position immediately; easing a camera
            // across the jump is what put the player outside the frame.
            rigReady = false;
            rigSearchTimer = 0.0;
        }
        teleportEnergy = Math.max(0.0, teleportEnergy - frameDelta / TELEPORT_MEMORY_SECONDS);
    }

    private void resetTeleportTracking() {
        observedPosition = null;
        teleportDirection = Vec3.ZERO;
        teleportEnergy = 0.0;
        teleportJumpDistance = 0.0;
        teleportSnap = false;
    }

    /**
     * Smooths the camera around the focus and looks for an open angle instead of
     * snapping straight into the player when a wall appears. Full distance is
     * preferred; shorter distances are only used when no open angle exists.
     */
    private Vec3 resolveGeometry(Entity entity, Vec3 focus, Vec3 desired) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!config.avoidGeometry || minecraft.level == null) {
            rigReady = false;
            resetClampSmoothing();
            return desired;
        }

        Vec3 offset = desired.subtract(focus);
        double baseHorizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (baseHorizontal < 1.0E-4) {
            return clampCamera(entity, focus, desired);
        }

        double baseAngle = Math.atan2(offset.z, offset.x);
        double baseVertical = offset.y;
        rigSearchTimer -= frameDelta;

        Candidate current = new Candidate(
            baseAngle + rigAngleOffset,
            baseHorizontal * rigDistanceFactor,
            baseVertical + rigHeightOffset
        );
        Vec3 currentPosition = candidatePosition(focus, current);
        boolean blocked = !isClear(entity, focus, currentPosition);
        // Search only when the current safe target actually needs replacing.
        // The old code ran the full global scan every 0.18 s even in open air,
        // and re-ran it every frame while clamped, which made the rig flip sides
        // around corners. Recentering is a separate, small, checked step.
        if (!rigReady || (rigSearchTimer <= 0.0 && blocked)) {
            Candidate chosen = chooseCandidate(entity, focus, baseAngle, baseHorizontal, baseVertical);
            rigAngleOffset = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(chosen.angle - baseAngle)));
            rigDistanceFactor = baseHorizontal < 1.0E-4 ? 1.0 : chosen.horizontal / baseHorizontal;
            rigHeightOffset = chosen.vertical - baseVertical;
            rigSearchTimer = RIG_SEARCH_INTERVAL;
        } else if (rigReady && rigSearchTimer <= 0.0) {
            recenterRig(entity, focus, baseAngle, baseHorizontal, baseVertical);
            rigSearchTimer = RIG_RECENTER_INTERVAL;
        }

        double targetAngle = baseAngle + rigAngleOffset;
        double targetHorizontal = baseHorizontal * rigDistanceFactor;
        double targetVertical = baseVertical + rigHeightOffset;
        if (!rigReady) {
            rigAngle = targetAngle;
            rigHorizontal = targetHorizontal;
            rigVertical = targetVertical;
            rigReady = true;
        } else {
            double angleStep = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(targetAngle - rigAngle)));
            rigAngle += angleStep * (1.0 - Math.exp(-frameDelta * 7.0));
            double horizontalRate = targetHorizontal < rigHorizontal ? 8.0 : 3.2;
            rigHorizontal += (targetHorizontal - rigHorizontal) * (1.0 - Math.exp(-frameDelta * horizontalRate));
            rigVertical += (targetVertical - rigVertical) * (1.0 - Math.exp(-frameDelta * 5.0));
        }

        Vec3 result = focus.add(new Vec3(
            Math.cos(rigAngle) * rigHorizontal,
            rigVertical,
            Math.sin(rigAngle) * rigHorizontal
        ));

        // The inward safety response stays immediate; clampCamera only eases
        // the outward recovery after the wall has cleared.
        return clampCamera(entity, focus, result);
    }

    /**
     * Slowly removes an avoidance offset once the direct shot is safe again.
     * Each proposed step is checked before it is accepted, so returning to the
     * original composition never pushes the camera through a corner.
     */
    private void recenterRig(Entity entity, Vec3 focus, double baseAngle,
                             double baseHorizontal, double baseVertical) {
        if (!rigReady) {
            return;
        }
        if (Math.abs(rigAngleOffset) < 0.001
            && Math.abs(rigHeightOffset) < 0.001
            && Math.abs(rigDistanceFactor - 1.0) < 0.001) {
            return;
        }
        double blend = 1.0 - Math.exp(-RIG_RECENTER_INTERVAL * RIG_RECENTER_RATE);
        double nextAngle = rigAngleOffset * (1.0 - blend);
        double nextHeight = rigHeightOffset * (1.0 - blend);
        double nextDistance = 1.0 + (rigDistanceFactor - 1.0) * (1.0 - blend);
        Candidate proposed = new Candidate(
            baseAngle + nextAngle,
            baseHorizontal * nextDistance,
            baseVertical + nextHeight
        );
        if (isClear(entity, focus, candidatePosition(focus, proposed))) {
            rigAngleOffset = nextAngle;
            rigHeightOffset = nextHeight;
            rigDistanceFactor = nextDistance;
        }
    }

    private Candidate chooseCandidate(Entity entity, Vec3 focus, double baseAngle, double baseHorizontal, double baseVertical) {
        boolean fast = speedEase() > 0.45;
        boolean contextual = config.shot == Shot.DYNAMIC && (miningWeight > 0.15 || combatWeight > 0.15);
        double currentAngle = rigReady ? baseAngle + rigAngleOffset : baseAngle;
        double currentHorizontal = baseHorizontal * (rigReady ? rigDistanceFactor : 1.0);
        double currentVertical = baseVertical + (rigReady ? rigHeightOffset : 0.0);
        double continuityWeight = fast || contextual ? 2.2 : 1.2;

        // Search the whole circle before shortening the boom. A strong penalty
        // for leaving the current side of the player, plus a smaller penalty for
        // leaving the authored angle, keeps the rig from flip-flopping between
        // equally valid corners while still letting it find a wide-open angle.
        for (double factor : RIG_DISTANCE_FACTORS) {
            Candidate best = null;
            double bestScore = Double.MAX_VALUE;
            for (double angleOffset : RIG_ANGLE_OFFSETS) {
                for (double heightOffset : RIG_HEIGHT_OFFSETS) {
                    Candidate candidate = new Candidate(
                        baseAngle + angleOffset,
                        baseHorizontal * factor,
                        baseVertical + heightOffset
                    );
                    Vec3 position = candidatePosition(focus, candidate);
                    if (!isClear(entity, focus, position)) {
                        continue;
                    }
                    double anglePenalty = fast || contextual ? 2.2 : 1.35;
                    double angleDelta = Math.abs(Math.toRadians(Mth.wrapDegrees(
                        Math.toDegrees(candidate.angle - currentAngle))));
                    double score = Math.abs(angleOffset) * anglePenalty
                        + Math.abs(heightOffset) * (fast ? 0.42 : 0.65)
                        + (1.0 - factor) * 4.8
                        + angleDelta * continuityWeight
                        + Math.abs(candidate.vertical - currentVertical) * 0.65
                        + Math.abs(candidate.horizontal - currentHorizontal) * 0.35;
                    if (heightOffset > 0.0) {
                        score -= 0.08;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }

        return new Candidate(currentAngle, Math.min(1.2, currentHorizontal), currentVertical);
    }

    private Vec3 candidatePosition(Vec3 focus, Candidate candidate) {
        return focus.add(new Vec3(
            Math.cos(candidate.angle) * candidate.horizontal,
            candidate.vertical,
            Math.sin(candidate.angle) * candidate.horizontal
        ));
    }

    private boolean isClear(Entity entity, Vec3 focus, Vec3 candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return true;
        }
        if (!isCameraSpaceClear(entity, candidate)) {
            return false;
        }
        Vec3 ray = candidate.subtract(focus);
        double length = ray.length();
        if (length < 1.0E-4) {
            return true;
        }
        HitResult hit = minecraft.level.clip(new ClipContext(
            focus,
            candidate,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            entity
        ));
        return hit.getType() == HitResult.Type.MISS
            || hit.getLocation().distanceToSqr(focus) >= length * length - 0.01;
    }

    private void resetRig() {
        rigReady = false;
        rigAngleOffset = 0.0;
        rigHeightOffset = 0.0;
        rigDistanceFactor = 1.0;
        rigSearchTimer = 0.0;
        resetClampSmoothing();
    }

    private void resetClampSmoothing() {
        clampOrigin = null;
        clampDirection = null;
        clampDistance = -1.0;
    }

    /**
     * Hard collision clamp used after all framing has been blended. The centre
     * ray and the camera volume are both enforced, so a thin wall can never
     * occupy the same frame position as the lens. Pulling away from a wall is
     * eased only after the candidate position has passed the collision test;
     * moving into a wall remains immediate.
     */
    private Vec3 clampCamera(Entity entity, Vec3 focus, Vec3 desired) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return desired;
        }
        // Normally the look target is the safest place to retreat towards.
        // If a combat or mining focus sits inside geometry, use the player's
        // eye instead so the clamp can never pull the lens into that block.
        Vec3 origin = isPointClear(entity, focus) ? focus : entity.getEyePosition();
        if (!isPointClear(entity, origin)) {
            origin = focus;
        }
        Vec3 offset = desired.subtract(origin);
        double length = offset.length();
        if (length <= 1.0E-4) {
            resetClampSmoothing();
            return desired;
        }
        Vec3 direction = offset.scale(1.0 / length);
        double maxDistance = length;
        HitResult hit = minecraft.level.clip(new ClipContext(
            origin,
            desired,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            entity
        ));
        if (hit.getType() != HitResult.Type.MISS) {
            double clearance = Mth.clamp(config.cameraMargin, 0.05, 1.5);
            maxDistance = Mth.clamp(
                hit.getLocation().distanceTo(origin) - clearance,
                0.0,
                length
            );
        }

        // The centre ray can miss while the camera volume clips a corner.
        // Scan back from the preferred point and take the furthest volume-clear
        // position. This also handles a player standing inside the clearance
        // radius: open space can start slightly farther out than the eye.
        if (!isCameraSpaceClear(entity, origin.add(direction.scale(maxDistance)))) {
            double clearDistance = 0.0;
            boolean found = false;
            for (int i = 32; i >= 0; i--) {
                double distance = maxDistance * i / 32.0;
                if (isCameraSpaceClear(entity, origin.add(direction.scale(distance)))) {
                    clearDistance = distance;
                    found = true;
                    break;
                }
            }
            maxDistance = found ? clearDistance : 0.0;
        }

        double distance = maxDistance;
        if (clampOrigin != null && clampDirection != null && clampDistance >= 0.0
            && origin.distanceToSqr(clampOrigin) < 2.25
            && clampDirection.dot(direction) > 0.96) {
            if (maxDistance < clampDistance) {
                // A new wall or corner: get out immediately.
                distance = maxDistance;
            } else {
                // The wall has cleared: ease the boom back out instead of
                // popping. The result is still checked before it is returned.
                double outward = 1.0 - Math.exp(-frameDelta * CLAMP_OUT_RATE);
                distance = clampDistance + (maxDistance - clampDistance) * outward;
            }
        }
        distance = Math.min(distance, maxDistance);
        Vec3 result = origin.add(direction.scale(distance));
        if (!isCameraSpaceClear(entity, result)) {
            result = origin.add(direction.scale(maxDistance));
            distance = maxDistance;
        }
        clampOrigin = origin;
        clampDirection = direction;
        clampDistance = distance;
        return result;
    }

    private boolean isPointClear(Entity entity, Vec3 pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return true;
        }
        double radius = 0.05;
        AABB box = new AABB(
            pos.x - radius, pos.y - radius, pos.z - radius,
            pos.x + radius, pos.y + radius, pos.z + radius
        );
        return minecraft.level.noBlockCollision(entity, box);
    }

    private boolean isCameraSpaceClear(Entity entity, Vec3 pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return true;
        }
        double radius = cameraRadius();
        AABB box = new AABB(
            pos.x - radius, pos.y - radius, pos.z - radius,
            pos.x + radius, pos.y + radius, pos.z + radius
        );
        return minecraft.level.noBlockCollision(entity, box);
    }

    private double cameraRadius() {
        return Mth.clamp(config.cameraMargin, 0.28, 0.8);
    }

    private record Framing(Vec3 position, Vec3 focus) {
    }

    private record Candidate(double angle, double horizontal, double vertical) {
    }
}
