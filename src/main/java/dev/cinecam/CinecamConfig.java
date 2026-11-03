package dev.cinecam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain JSON config stored at {@code config/cinecam.json}.
 */
public final class CinecamConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("cinecam.json");

    /** Active shot. */
    public Shot shot = Shot.ORBIT;
    /** Base playback speed multiplier. */
    public double speed = 1.0;
    /** Base orbit radius in blocks. */
    public double radius = 6.0;
    /** Base camera height in blocks, relative to the player's feet. */
    public double height = 2.4;
    /** Height of the point the camera looks at, relative to the player's feet. */
    public double targetHeight = 1.2;
    /** Follow the player as they move. When false the shot is anchored where it started. */
    public boolean follow = true;
    /** How quickly the camera catches up to a moving player (higher is tighter). */
    public double followRate = 4.0;
    /** Seconds spent easing in and out of the shot. */
    public double introSeconds = 1.5;
    /** Field of view used while the shot is fully engaged. */
    public int fov = 55;
    /** Draw cinematic black bars. */
    public boolean letterbox = true;
    /** Letterbox aspect ratio (2.39 is classic cinemascope). */
    public double letterboxRatio = 2.39;
    /** Hide the vanilla HUD while the shot is engaged. */
    public boolean hideHud = true;
    /** Maximum roll (Dutch angle) in degrees. */
    public double roll = 2.5;
    /** Handheld micro-shake in blocks. Set to 0 for a locked-off camera. */
    public double shake = 0.02;

    public static CinecamConfig load(Logger logger) {
        CinecamConfig config = new CinecamConfig();
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                CinecamConfig loaded = GSON.fromJson(reader, CinecamConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception exception) {
                logger.warn("[Cinecam] Could not read {}; using defaults", PATH, exception);
            }
        }
        config.normalize();
        config.save(logger);
        return config;
    }

    public void save(Logger logger) {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            logger.warn("[Cinecam] Could not save {}", PATH, exception);
        }
    }

    public void normalize() {
        if (shot == null) {
            shot = Shot.ORBIT;
        }
        speed = Mth.clamp(speed, 0.1, 5.0);
        radius = Mth.clamp(radius, 1.0, 40.0);
        height = Mth.clamp(height, -10.0, 40.0);
        targetHeight = Mth.clamp(targetHeight, 0.0, 3.0);
        followRate = Mth.clamp(followRate, 0.5, 20.0);
        introSeconds = Mth.clamp(introSeconds, 0.0, 6.0);
        fov = Mth.clamp(fov, 20, 110);
        letterboxRatio = Mth.clamp(letterboxRatio, 1.0, 4.0);
        roll = Mth.clamp(roll, -30.0, 30.0);
        shake = Mth.clamp(shake, 0.0, 0.5);
    }

    public void copyFrom(CinecamConfig other) {
        this.shot = other.shot;
        this.speed = other.speed;
        this.radius = other.radius;
        this.height = other.height;
        this.targetHeight = other.targetHeight;
        this.follow = other.follow;
        this.followRate = other.followRate;
        this.introSeconds = other.introSeconds;
        this.fov = other.fov;
        this.letterbox = other.letterbox;
        this.letterboxRatio = other.letterboxRatio;
        this.hideHud = other.hideHud;
        this.roll = other.roll;
        this.shake = other.shake;
    }
}
