package dev.cinecam;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cinecam.camera.CineCamera;
import dev.cinecam.command.CinecamCommand;
import dev.cinecam.gui.CinecamScreen;
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
    private KeyMapping manualKey;
    private final boolean[] keyWasDown = new boolean[7];

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
        manualKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.cinecam.manual", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        CinecamCommand.register();

        LOGGER.info("[Cinecam] ready for Minecraft 26.2 with {} shots", Shot.values().length);
    }

    private void onEndTick(Minecraft minecraft) {
        pollInput();
        CinecamScreen.tickOpen();
    }

    /**
     * Polls the shortcuts from both the client tick and the camera render pass.
     * The render pass matters for clients that leave the mouse ungrabbed or own
     * input with a non-pausing screen: vanilla's click queue can be empty there
     * even though the physical key is down. Polling is edge-based, so calling
     * this from both places cannot toggle twice for one press.
     */
    public void pollInput() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null || camera == null) {
            return;
        }
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
        boolean manualPressed = pressed(manualKey, 6, minecraft);
        if (manualPressed && minecraft.gui.screen() == null) {
            camera.toggleManual();
        }
        pollManualAxes(minecraft);
        camera.tick();
    }

    /**
     * Raw WASD reads only feed the manual camera mode. The player still moves
     * normally; manual framing is an additive aim offset, not an input lock.
     */
    private void pollManualAxes(Minecraft minecraft) {
        if (camera == null) {
            return;
        }
        if (!camera.isManualEnabled() || !camera.isEnabled()
            || minecraft.gui.screen() != null || minecraft.getWindow() == null) {
            camera.setManualAxes(0.0, 0.0);
            return;
        }
        long handle = minecraft.getWindow().handle();
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE) {
            camera.setManualAxes(0.0, 0.0);
            return;
        }
        double forward = (keyDown(handle, GLFW.GLFW_KEY_W) ? 1.0 : 0.0)
            - (keyDown(handle, GLFW.GLFW_KEY_S) ? 1.0 : 0.0);
        double right = (keyDown(handle, GLFW.GLFW_KEY_D) ? 1.0 : 0.0)
            - (keyDown(handle, GLFW.GLFW_KEY_A) ? 1.0 : 0.0);
        camera.setManualAxes(forward, right);
    }

    private static boolean keyDown(long handle, int key) {
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }

    /**
     * Consumes the mouse wheel only while manual framing is live, so zooming
     * the lens does not also change the hotbar slot. GUI screens keep their
     * normal scroll handling.
     */
    public boolean handleManualScroll(double amount) {
        if (camera == null || amount == 0.0
            || !camera.isManualEnabled() || !camera.isEnabled()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui.screen() != null) {
            return false;
        }
        camera.adjustManualZoom(amount);
        return true;
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

        long handle = minecraft.getWindow().handle();
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE) {
            keyWasDown[index] = false;
            return clicked;
        }

        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        boolean down = false;
        if (key.getValue() >= 0 && key.getType() == InputConstants.Type.KEYSYM) {
            down = GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
        } else if (key.getValue() >= 0 && key.getType() == InputConstants.Type.MOUSE) {
            down = GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }

        boolean edge = down && !keyWasDown[index];
        keyWasDown[index] = down;
        return clicked || edge;
    }

    public void reloadConfig() {
        CinecamConfig loaded = CinecamConfig.load(LOGGER);
        config.copyFrom(loaded);
        camera.reloadFromConfig();
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
