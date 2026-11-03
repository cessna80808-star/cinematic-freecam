package dev.cinecam.hud;

import dev.cinecam.CinecamConfig;
import dev.cinecam.camera.CineCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Overlay drawing for cinematic bars and the shot name toast. Uses the 26.2
 * GUI extractor, so everything is submitted into the normal GUI render state.
 */
public final class CinecamOverlay {
    private CinecamOverlay() {
    }

    public static void draw(GuiGraphicsExtractor graphics, CineCamera camera) {
        CinecamConfig config = camera.config();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int bar = 0;

        if (config.letterbox && config.letterboxRatio > 0.0) {
            bar = (int) Math.max(0.0, Math.round((height - width / config.letterboxRatio) / 2.0));
            if (bar > 0) {
                graphics.fill(0, 0, width, bar, 0xFF000000);
                graphics.fill(0, height - bar, width, height, 0xFF000000);
            }
        }

        String toast = camera.toastText();
        if (toast.isEmpty()) {
            return;
        }
        float alpha = camera.toastAlpha();
        if (alpha <= 0.0F) {
            return;
        }

        int color = (((int) (alpha * 255.0F)) & 0xFF) << 24 | 0x00FFFFFF;
        int y = Math.max(4, height - bar - 16);
        graphics.text(Minecraft.getInstance().font, Component.literal(toast), 8, y, color, true);
    }
}
