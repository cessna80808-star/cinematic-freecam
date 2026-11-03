package dev.cinecam.gui;

import dev.cinecam.CinecamClient;
import dev.cinecam.CinecamConfig;
import dev.cinecam.Shot;
import dev.cinecam.camera.CineCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The Cinecam studio. Opened with {@code /freecam} or {@code /cinecam gui}.
 *
 * <p>The left side shows a live, animated path preview for every shot; the
 * right side exposes the settings that matter while composing a shot. Every
 * control writes through to the same {@link CinecamConfig} the camera uses, so
 * changes are visible immediately and survive a restart.</p>
 */
public final class CinecamScreen extends Screen {
    private static final int MARGIN = 16;
    private static final int HEADER_HEIGHT = 44;
    private static final int FOOTER_HEIGHT = 36;
    private static final int PANEL_GAP = 10;
    private static final int CARD_GAP = 6;
    private static final int SETTINGS_ROWS = 14;
    private static final int SETTINGS_GAP = 2;

    private static final int COLOR_OVERLAY = 0xC4080C12;
    private static final int COLOR_PANEL = 0xE6101720;
    private static final int COLOR_PANEL_ALT = 0xE6141D27;
    private static final int COLOR_BORDER = 0xFF263241;
    private static final int COLOR_BORDER_SOFT = 0xFF1C2834;
    private static final int COLOR_TEXT = 0xFFE8EEF5;
    private static final int COLOR_MUTED = 0xFF8C9AAB;
    private static final int COLOR_DIM = 0xFF5E6B7B;
    private static final int COLOR_ACCENT = 0xFF5FE3C0;
    private static final int COLOR_GOLD = 0xFFFFB454;

    private final CineCamera camera;
    private final List<ConfigSlider> sliders = new ArrayList<>();

    private Button cameraButton;
    private Button dynamicButton;
    private Button speedButton;
    private Button frameButton;
    private Button hudButton;
    private Button directorButton;
    private Button geometryButton;

    private int panelTop;
    private int panelBottom;
    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;

    public CinecamScreen() {
        super(Component.literal("Cinecam Studio"));
        CinecamClient client = CinecamClient.getInstance();
        this.camera = client == null ? null : client.camera();
    }

    public static void open() {
        if (CinecamClient.getInstance() == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new CinecamScreen());
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.sliders.clear();

        int gap = Math.max(8, Math.min(12, this.width / 70));
        int panelWidth = this.width - MARGIN * 2;
        this.panelTop = HEADER_HEIGHT;
        this.panelBottom = this.height - FOOTER_HEIGHT;
        this.leftX = MARGIN;
        this.leftWidth = Math.max(190, (int) ((panelWidth - gap) * 0.63));
        this.rightX = this.leftX + this.leftWidth + gap;
        this.rightWidth = this.width - MARGIN - this.rightX;

        if (this.camera == null) {
            return;
        }

        // A thin gold line under the header makes the studio read instantly.
        this.addRenderableWidget(Button.builder(
            Component.literal("Done"),
            button -> this.onClose()
        ).bounds(this.width - MARGIN - 62, 12, 62, 20).build());

        this.buildPreviewCards();
        this.buildSettings();
        this.refreshLabels();
    }

    private void buildPreviewCards() {
        int gridTop = this.panelTop + 26;
        int gridBottom = this.panelBottom - 8;
        int gridHeight = Math.max(80, gridBottom - gridTop);
        int cardWidth = Math.max(54, (this.leftWidth - CARD_GAP) / 2);
        int cardHeight = Math.max(22, (gridHeight - CARD_GAP * 4) / 5);

        Shot[] shots = Shot.values();
        for (int index = 0; index < shots.length; index++) {
            int column = index % 2;
            int row = index / 2;
            int x = this.leftX + column * (cardWidth + CARD_GAP);
            int y = gridTop + row * (cardHeight + CARD_GAP);
            this.addRenderableWidget(new ShotCard(x, y, cardWidth, cardHeight, shots[index]));
        }
    }

    private void buildSettings() {
        int settingsTop = this.panelTop + 28;
        int available = Math.max(100, this.panelBottom - settingsTop - 6);
        int rowHeight = Math.max(14, Math.min(20,
            (available - (SETTINGS_ROWS - 1) * SETTINGS_GAP) / SETTINGS_ROWS));
        int used = SETTINGS_ROWS * rowHeight + (SETTINGS_ROWS - 1) * SETTINGS_GAP;
        int x = this.rightX + 8;
        int y = settingsTop + Math.max(0, (available - used) / 2);
        int width = Math.max(80, this.rightWidth - 16);

        this.cameraButton = this.addButton(x, y, width, rowHeight, "Camera", button -> {
            this.camera.toggle();
            this.refreshLabels();
        });
        y += rowHeight + SETTINGS_GAP;

        this.dynamicButton = this.addButton(x, y, width, rowHeight, "Adaptive", button -> {
            this.camera.toggleDynamic();
            this.refreshLabels();
        });
        y += rowHeight + SETTINGS_GAP;

        this.speedButton = this.addButton(x, y, width, rowHeight, "Playback", button -> {
            this.camera.cycleSpeed();
            this.refreshLabels();
        });
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "FOV", 30.0, 100.0,
            () -> this.camera.config().fov,
            value -> this.camera.config().fov = (int) Math.round(value), "%.0f");
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "Distance", 2.0, 16.0,
            () -> this.camera.config().radius,
            value -> this.camera.config().radius = value, "%.1f");
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "Height", 0.0, 8.0,
            () -> this.camera.config().height,
            value -> this.camera.config().height = value, "%.1f");
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "Aim height", 0.6, 2.4,
            () -> this.camera.config().targetHeight,
            value -> this.camera.config().targetHeight = value, "%.1f");
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "Wall margin", 0.05, 1.0,
            () -> this.camera.config().cameraMargin,
            value -> this.camera.config().cameraMargin = value, "%.2f");
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "Intro", 0.0, 4.0,
            () -> this.camera.config().introSeconds,
            value -> this.camera.config().introSeconds = value, "%.1fs");
        y += rowHeight + SETTINGS_GAP;

        this.addSlider(x, y, width, rowHeight, "Shake", 0.0, 0.12,
            () -> this.camera.config().shake,
            value -> this.camera.config().shake = value, "%.3f");
        y += rowHeight + SETTINGS_GAP;

        this.frameButton = this.addButton(x, y, width, rowHeight, "Frame", button -> {
            this.camera.toggleLetterbox();
            this.refreshLabels();
        });
        y += rowHeight + SETTINGS_GAP;

        this.hudButton = this.addButton(x, y, width, rowHeight, "HUD", button -> {
            CinecamConfig config = this.camera.config();
            config.hideHud = !config.hideHud;
            config.save(CinecamClient.LOGGER);
            this.refreshLabels();
        });
        y += rowHeight + SETTINGS_GAP;

        this.directorButton = this.addButton(x, y, width, rowHeight, "Director", button -> {
            this.camera.toggleDirector();
            this.refreshLabels();
        });
        y += rowHeight + SETTINGS_GAP;

        this.geometryButton = this.addButton(x, y, width, rowHeight, "Wall avoid", button -> {
            CinecamConfig config = this.camera.config();
            config.avoidGeometry = !config.avoidGeometry;
            config.save(CinecamClient.LOGGER);
            this.refreshLabels();
        });
    }

    private Button addButton(int x, int y, int width, int height, String label, Button.OnPress onPress) {
        Button button = Button.builder(Component.literal(label), onPress)
            .bounds(x, y, width, height)
            .build();
        this.addRenderableWidget(button);
        return button;
    }

    private void addSlider(int x, int y, int width, int height, String label, double min, double max,
                           DoubleSupplier getter, DoubleConsumer setter, String format) {
        ConfigSlider slider = new ConfigSlider(x, y, width, height, label, min, max, getter, setter, format);
        this.sliders.add(slider);
        this.addRenderableWidget(slider);
    }

    @Override
    public void tick() {
        super.tick();
        this.refreshLabels();
    }

    private void refreshLabels() {
        if (this.camera == null) {
            return;
        }
        CinecamConfig config = this.camera.config();
        setButtonLabel(this.cameraButton, "Camera: " + (this.camera.isEnabled() ? "ON" : "OFF"));
        setButtonLabel(this.dynamicButton, "Adaptive: " + (this.camera.isDynamicEnabled() ? "ON" : "CLASSIC"));
        setButtonLabel(this.speedButton, "Playback: " + formatSpeed(this.camera.speedFactor()));
        setButtonLabel(this.frameButton, "Frame: " + (config.letterbox ? "2.39:1" : "FULL"));
        setButtonLabel(this.hudButton, "HUD: " + (config.hideHud ? "HIDDEN" : "SHOWN"));
        setButtonLabel(this.directorButton, "Director: " + (this.camera.isDirectorEnabled() ? "ON" : "OFF"));
        setButtonLabel(this.geometryButton, "Wall avoid: " + (config.avoidGeometry ? "ON" : "OFF"));
    }

    private static void setButtonLabel(Button button, String label) {
        if (button == null) {
            return;
        }
        if (!button.getMessage().getString().equals(label)) {
            button.setMessage(Component.literal(label));
        }
    }

    private static String formatSpeed(double speed) {
        if (speed == Math.rint(speed)) {
            return String.format(Locale.ROOT, "%.0fx", speed);
        }
        return String.format(Locale.ROOT, "%.2fx", speed).replaceAll("0x$", "x");
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, this.width, this.height, COLOR_OVERLAY);

        drawPanel(graphics, this.leftX, this.panelTop, this.leftWidth, this.panelBottom - this.panelTop);
        drawPanel(graphics, this.rightX, this.panelTop, this.rightWidth, this.panelBottom - this.panelTop);

        graphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, COLOR_BORDER);
        graphics.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height - FOOTER_HEIGHT + 1, COLOR_BORDER);

        graphics.text(this.font, "CINECAM STUDIO", MARGIN, 13, COLOR_TEXT, true);
        if (this.camera == null) {
            graphics.text(this.font, "camera unavailable", MARGIN, 26, COLOR_MUTED, false);
            return;
        }

        String status = this.camera.isEnabled() ? "CAMERA ON" : "CAMERA OFF";
        String mode = this.camera.isDynamicEnabled() ? "ADAPTIVE" : "CLASSIC";
        graphics.text(this.font, status, MARGIN, 26,
            this.camera.isEnabled() ? COLOR_ACCENT : COLOR_DIM, false);
        graphics.text(this.font, " / " + mode, MARGIN + this.font.width(status), 26, COLOR_MUTED, false);

        graphics.text(this.font, "SHOT PREVIEWS", this.leftX + 10, this.panelTop + 8, COLOR_MUTED, false);
        graphics.text(this.font, "LIVE PATH PREVIEW", this.leftX + 10, this.panelTop + 18, COLOR_DIM, false);
        graphics.text(this.font, "SETTINGS", this.rightX + 10, this.panelTop + 8, COLOR_MUTED, false);

        Shot shot = this.camera.shot();
        String description = shot.displayName() + " - " + shot.description();
        int footerWidth = this.width - MARGIN * 2 - 8;
        graphics.text(this.font, this.font.plainSubstrByWidth(description, footerWidth),
            MARGIN, this.height - 28, COLOR_TEXT, false);
        graphics.text(this.font,
            this.font.plainSubstrByWidth(
                "Click a preview to select  |  F6 camera  |  F4 adaptive  |  Esc close",
                footerWidth),
            MARGIN, this.height - 16, COLOR_DIM, false);
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, COLOR_PANEL_ALT);
        graphics.outline(x, y, x + width, y + height, COLOR_BORDER);
        graphics.fill(x, y, x + width, y + 2, COLOR_ACCENT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(null);
    }

    private final class ShotCard extends AbstractWidget {
        private final Shot shot;

        private ShotCard(int x, int y, int width, int height, Shot shot) {
            super(x, y, width, height, Component.literal(shot.displayName()));
            this.shot = shot;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (CinecamScreen.this.camera == null) {
                return;
            }
            int x = this.getX();
            int y = this.getY();
            int width = this.width;
            int height = this.height;
            boolean selected = CinecamScreen.this.camera.shot() == this.shot;
            boolean hovered = this.isHovered();

            int background = selected ? 0xFF1B2831 : hovered ? 0xFF16222D : 0xFF101821;
            int border = selected ? COLOR_GOLD : hovered ? 0xFF3B4D60 : COLOR_BORDER_SOFT;
            graphics.fill(x, y, x + width, y + height, background);
            graphics.outline(x, y, x + width, y + height, border);
            if (selected) {
                graphics.fill(x, y, x + 3, y + height, COLOR_GOLD);
            }

            int padding = 5;
            int pathWidth = Math.max(26, Math.min(width - 42, Math.round(width * 0.43F)));
            int pathHeight = Math.max(10, height - padding * 2);
            int centerX = x + padding + pathWidth / 2;
            int centerY = y + height / 2;
            double radius = Math.max(4.0, Math.min(pathWidth, pathHeight) * 0.42);

            graphics.outline(centerX - (int) radius, centerY - (int) radius,
                centerX + (int) radius, centerY + (int) radius, 0x332A3A48);
            graphics.fill(centerX - (int) radius, centerY, centerX + (int) radius, centerY + 1, 0x221F2D3A);

            double period = this.shot == Shot.FLYBY ? 18.0 : 12.0;
            int samples = 40;
            int pathColor = selected ? 0xCC66E3C6 : 0x665A7480;
            for (int index = 0; index <= samples; index++) {
                double[] point = previewPoint(this.shot, period * index / samples);
                int px = centerX + (int) Math.round(point[0] * radius);
                int py = centerY + (int) Math.round(point[1] * radius);
                graphics.fill(px, py, px + 1, py + 1, pathColor);
            }

            double time = System.currentTimeMillis() / 1000.0 * 0.7;
            double[] current = previewPoint(this.shot, time);
            int cameraX = centerX + (int) Math.round(current[0] * radius);
            int cameraY = centerY + (int) Math.round(current[1] * radius);
            drawLine(graphics, cameraX, cameraY, centerX, centerY, selected ? 0x885FE3C0 : 0x445A7480);

            double angle = Math.atan2(centerY - cameraY, centerX - cameraX);
            int reach = (int) radius + 8;
            drawLine(graphics, cameraX, cameraY,
                centerX + (int) (Math.cos(angle - 0.38) * reach),
                centerY + (int) (Math.sin(angle - 0.38) * reach),
                selected ? 0x665FE3C0 : 0x225A7480);
            drawLine(graphics, cameraX, cameraY,
                centerX + (int) (Math.cos(angle + 0.38) * reach),
                centerY + (int) (Math.sin(angle + 0.38) * reach),
                selected ? 0x665FE3C0 : 0x225A7480);

            graphics.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 2, COLOR_TEXT);
            graphics.fill(cameraX - 2, cameraY - 2, cameraX + 2, cameraY + 2,
                selected ? COLOR_GOLD : COLOR_ACCENT);

            int textX = x + padding + pathWidth + 7;
            int textWidth = Math.max(1, width - (textX - x) - padding);
            String name = CinecamScreen.this.font.plainSubstrByWidth(this.shot.displayName(), textWidth);
            graphics.text(CinecamScreen.this.font, name, textX, y + padding,
                selected ? 0xFFFFE1B0 : COLOR_TEXT, false);

            if (height >= 38 && textWidth > 26) {
                String tag = this.shot == Shot.DYNAMIC ? "ADAPTIVE" : "SHOT";
                graphics.text(CinecamScreen.this.font, tag, textX, y + height - padding - CinecamScreen.this.font.lineHeight,
                    selected ? COLOR_ACCENT : COLOR_DIM, false);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (CinecamScreen.this.camera != null && event.button() == 0) {
                CinecamScreen.this.camera.setShot(this.shot);
                CinecamScreen.this.refreshLabels();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    /**
     * Stylised top-down path used by the preview cards. It is deliberately
     * independent of the camera's live rig: the card shows the shape of the
     * move even when the real camera is avoiding a wall.
     */
    private static double[] previewPoint(Shot shot, double time) {
        double theta;
        double radius = 1.0;
        double x;
        double y;
        switch (shot) {
            case ORBIT -> {
                theta = time * 0.20;
                x = Math.cos(theta);
                y = Math.sin(theta);
            }
            case SPIRAL -> {
                theta = time * 0.26;
                radius = 0.90 + 0.18 * Math.sin(time * 0.21);
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            case CRANE -> {
                theta = time * 0.16;
                double progress = 0.5 - 0.5 * Math.cos(time * 0.10);
                radius = 1.20 - 0.30 * progress;
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            case DOLLY -> {
                theta = time * 0.19;
                radius = 1.0 + 0.28 * Math.sin(time * 0.13);
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            case FLYBY -> {
                double progress = (time % 18.0) / 18.0;
                theta = -2.1 + progress * 4.2;
                radius = 0.45 + 0.45 * Math.sin(Math.PI * progress);
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            case FIGURE8 -> {
                theta = time * 0.18;
                x = Math.cos(theta);
                y = Math.sin(2.0 * theta) * 0.65;
            }
            case TOPDOWN -> {
                theta = time * 0.12;
                radius = 0.48;
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            case PENDULUM -> {
                theta = 1.25 * Math.sin(time * 0.35);
                x = Math.sin(theta) * 0.90;
                y = -Math.cos(theta) * 0.90;
            }
            case VERTIGO -> {
                theta = time * 0.14;
                double cycle = 0.5 - 0.5 * Math.cos(time * 0.22);
                radius = 1.0 - 0.35 * cycle;
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            case DYNAMIC -> {
                theta = time * 0.14;
                radius = 0.80 + 0.10 * Math.sin(time * 0.11);
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
            }
            default -> {
                x = 0.0;
                y = 0.0;
            }
        }
        return new double[]{x, y};
    }

    private static void drawLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps <= 0) {
            graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            return;
        }
        for (int index = 0; index <= steps; index++) {
            int x = Math.round(x0 + (x1 - x0) * (float) index / steps);
            int y = Math.round(y0 + (y1 - y0) * (float) index / steps);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private final class ConfigSlider extends AbstractSliderButton {
        private String label;
        private double min;
        private double max;
        private DoubleSupplier getter;
        private DoubleConsumer setter;
        private String format;

        private ConfigSlider(int x, int y, int width, int height, String label,
                             double min, double max, DoubleSupplier getter,
                             DoubleConsumer setter, String format) {
            super(x, y, width, height, Component.empty(),
                normalizeValue(getter.getAsDouble(), min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.format = format;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (this.label == null || this.getter == null || this.format == null) {
                return;
            }
            double current = this.getter.getAsDouble();
            this.setMessage(Component.literal(
                this.label + ": " + String.format(Locale.ROOT, this.format, current)));
        }

        @Override
        protected void applyValue() {
            if (this.setter == null) {
                return;
            }
            double current = this.min + this.value * (this.max - this.min);
            this.setter.accept(current);
            this.updateMessage();
            if (CinecamScreen.this.camera != null) {
                CinecamScreen.this.camera.config().save(CinecamClient.LOGGER);
            }
        }

        @Override
        protected void setValue(double value) {
            super.setValue(value);
            this.updateMessage();
        }
    }

    private static double normalizeValue(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return Mth.clamp((value - min) / (max - min), 0.0, 1.0);
    }
}
