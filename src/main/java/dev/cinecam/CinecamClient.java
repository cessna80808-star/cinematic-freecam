package dev.cinecam;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cinecam.camera.CineCamera;
import dev.cinecam.command.CinecamCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cinecam client entrypoint. Everything is client-side: the server never sees
 * the camera and your movement stays untouched.
 */
public final class CinecamClient implements ClientModInitializer {
    public static final String MOD_ID = "cinecam";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CinecamClient instance;

    private final CinecamConfig config = new CinecamConfig();
    private CineCamera camera;
    private KeyMapping toggleKey;
    private KeyMapping shotKey;
    private KeyMapping speedKey;
    private KeyMapping freezeKey;
    private KeyMapping directorKey;
    private KeyMapping dynamicKey;

    @Override
    public void onInitializeClient() {
        instance = this;

        CinecamConfig loaded = CinecamConfig.load(LOGGER);
        config.copyFrom(loaded);
        camera = new CineCamera(config);

        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "cinecam")
        );
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, category
        ));
        shotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.shot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, category
        ));
        speedKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.speed", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category
        ));
        freezeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.freeze", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category
        ));
        directorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.director", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, category
        ));
        dynamicKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.dynamic", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        CinecamCommand.register();

        LOGGER.info("[Cinecam] ready for Minecraft 26.2 with {} shots", Shot.values().length);
    }

    private void onEndTick(Minecraft minecraft) {
        while (toggleKey.consumeClick()) {
            camera.toggle();
        }
        while (shotKey.consumeClick()) {
            camera.nextShot();
        }
        while (speedKey.consumeClick()) {
            camera.cycleSpeed();
        }
        while (freezeKey.consumeClick()) {
            camera.toggleFreeze();
        }
        while (directorKey.consumeClick()) {
            camera.toggleDirector();
        }
        while (dynamicKey.consumeClick()) {
            camera.toggleDynamic();
        }
        camera.tick();
    }

    public void reloadConfig() {
        CinecamConfig loaded = CinecamConfig.load(LOGGER);
        config.copyFrom(loaded);
        camera.showToast("Config reloaded");
    }

    public static CinecamClient getInstance() {
        return instance;
    }

    public static CineCamera cameraInstance() {
        return instance == null ? null : instance.camera;
    }

    public CinecamConfig config() {
        return config;
    }

    public CineCamera camera() {
        return camera;
    }
}
