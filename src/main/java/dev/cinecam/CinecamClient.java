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
    private final boolean[] keyWasDown = new boolean[6];

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
        if (pressed(toggleKey, 0, minecraft)) {
            camera.toggle();
        }
        if (pressed(shotKey, 1, minecraft)) {
            camera.nextShot();
        }
        if (pressed(speedKey, 2, minecraft)) {
            camera.cycleSpeed();
        }
        if (pressed(freezeKey, 3, minecraft)) {
            camera.toggleFreeze();
        }
        if (pressed(directorKey, 4, minecraft)) {
            camera.toggleDirector();
        }
        if (pressed(dynamicKey, 5, minecraft)) {
            camera.toggleDynamic();
        }
        camera.tick();
    }

    /**
     * Vanilla key mappings stop producing clicks while an ungrabbed-mouse GUI
     * (for example Nebula's) owns input. Polling the physical key as a fallback
     * keeps Cinecam's shortcuts working there, without double-firing when the
     * normal click queue also saw the press.
     */
    private boolean pressed(KeyMapping mapping, int index, Minecraft minecraft) {
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }

        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        boolean down = false;
        if (key.getValue() >= 0 && key.getType() == InputConstants.Type.KEYSYM) {
            down = GLFW.glfwGetKey(minecraft.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        } else if (key.getValue() >= 0 && key.getType() == InputConstants.Type.MOUSE) {
            down = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }

        boolean edge = down && !keyWasDown[index];
        keyWasDown[index] = down;
        return clicked || edge;
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
