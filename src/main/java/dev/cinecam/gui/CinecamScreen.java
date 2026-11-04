package dev.cinecam.gui;

import dev.cinecam.CinecamClient;
import dev.cinecam.CinecamConfig;
import dev.cinecam.Shot;
import dev.cinecam.camera.CineCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
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
 * The Cinecam studio. A compact editing desk rather than a settings dump:
 * a static shot rail on the left, one live path monitor in the middle, and a
 * tabbed setup deck on the right.
 *
 * <p>The layout is sized against Minecraft's GUI-scaled coordinate system, so
 * it stays usable at the 426x240 canvas produced by common automatic GUI
 * scales instead of relying on a large window.</p>
 */
public final class CinecamScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int HEADER_HEIGHT = 34;
    private static final int FOOTER_HEIGHT = 18;
    private static final int GAP = 6;
    private static final int ROW_GAP = 2;
    private static final int CONTROL_ROWS = 7;
    private static final int TAB_HEIGHT = 16;

    private static final int COLOR_INK_TOP = 0xFA0B1114;
    private static final int COLOR_INK_BOTTOM = 0xFE050708;
    private static final int COLOR_PANEL = 0xFF0E1519;
    private static final int COLOR_PANEL_LOW = 0xFF090E11;
    private static final int COLOR_RULE = 0xFF26343A;
    private static final int COLOR_RULE_SOFT = 0xFF1A252A;
    private static final int COLOR_CHALK = 0xFFE8E3D8;
    private static final int COLOR_SMOKE = 0xFF98A3A5;
    private static final int COLOR_DIM = 0xFF7E8A8D;
    private static final int COLOR_AMBER = 0xFFDDAA55;
    private static final int COLOR_AMBER_DIM = 0x66DDAA55;
    private static final int COLOR_TALLY = 0xFFC7634B;
    private static final int COLOR_HOVER = 0x261FFFFF;
    private static final int COLOR_SELECT = 0x2EDDAA55;
    private static final int COLOR_GATE = 0xFF4E5F63;
    private static final int COLOR_PATH = 0xC8DDAA55;
    private static final int COLOR_PATH_OFF = 0x66DDAA55;

    private static boolean openRequested;

    private final CineCamera camera;
    private final List<PageControl> pageControls = new ArrayList<>();

    private ControlTab controlTab = ControlTab.LENS;
    private DeckButton cameraButton;
    private DeckButton dynamicButton;
    private DeckButton speedButton;
    private DeckButton frameButton;
    private DeckButton hudButton;
    private DeckButton directorButton;
    private DeckButton geometryButton;
    private DeckButton seeThroughButton;
    private DeckButton manualButton;
    private DeckButton manualResetButton;

    private int bodyTop;
    private int bodyBottom;
    private int leftX;
    private int leftWidth;
    private int monitorX;
    private int monitorWidth;
    private int deckX;
    private int deckWidth;
    private int tabY;
    private int deckRowStart;
    private int deckRowHeight;
    private int deckControlX;
    private int deckControlWidth;
    private boolean compactTabs;

    public CinecamScreen() {
        super(Component.literal("Cinecam Studio"));
        CinecamClient client = CinecamClient.getInstance();
        this.camera = client == null ? null : client.camera();
    }

    public static void open() {
        if (CinecamClient.getInstance() == null) {
            return;
        }
        // A client command is dispatched while ChatScreen is still current.
        // Vanilla closes that chat screen immediately afterwards, which would
        // erase a screen opened inline. Defer until a later client tick.
        openRequested = true;
    }

    /**
     * Opens the studio once the chat screen that ran the command has closed.
     * Called from the client end-tick hook.
     */
    public static void tickOpen() {
        if (!openRequested) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null) {
            return;
        }
        openRequested = false;
        minecraft.gui.setScreen(new CinecamScreen());
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.pageControls.clear();

        int bodyWidth = this.width - MARGIN * 2;
        int gap = Math.max(4, Math.min(GAP, this.width / 96));
        this.bodyTop = HEADER_HEIGHT;
        this.bodyBottom = this.height - FOOTER_HEIGHT;
        this.leftWidth = Math.max(84, Math.round(bodyWidth * 0.24F));
        this.deckWidth = Math.max(124, Math.round(bodyWidth * 0.36F));
        int monitorSpace = bodyWidth - this.leftWidth - this.deckWidth - gap * 2;
        if (monitorSpace < 106) {
            this.leftWidth = Math.max(74, Math.round(bodyWidth * 0.21F));
            this.deckWidth = Math.max(108, Math.round(bodyWidth * 0.32F));
            monitorSpace = bodyWidth - this.leftWidth - this.deckWidth - gap * 2;
        }

        this.leftX = MARGIN;
        this.monitorX = this.leftX + this.leftWidth + gap;
        this.monitorWidth = monitorSpace;
        this.deckX = this.monitorX + this.monitorWidth + gap;
        this.deckWidth = this.width - MARGIN - this.deckX;

        this.addRenderableWidget(new DeckButton(
            this.width - MARGIN - 46, 8, 46, TAB_HEIGHT,
            "Close", this::onClose, true
        ));

        if (this.camera == null) {
            return;
        }

        this.buildShotRail();
        this.buildDeck();
        this.refreshLabels();
        this.applyControlTab();
    }

    private void buildShotRail() {
        int top = this.bodyTop + 22;
        int available = Math.max(48, this.bodyBottom - 4 - top);
        int rowHeight = Math.max(16, Math.min(38, available / Shot.values().length));
        int x = this.leftX + 4;
        int width = this.leftWidth - 8;
        int y = top;
        for (Shot shot : Shot.values()) {
            this.addRenderableWidget(new ShotRow(x, y, width, rowHeight, shot));
            y += rowHeight;
        }
    }

    private void buildDeck() {
        this.tabY = this.bodyTop + 20;
        this.deckControlX = this.deckX + 7;
        this.deckControlWidth = this.deckWidth - 14;

        ControlTab[] tabs = ControlTab.values();
        this.compactTabs = false;
        int tabGap = this.deckControlWidth < 140 ? 1 : 2;
        int availableTabWidth = this.deckControlWidth - tabGap * (tabs.length - 1);
        int labelsWidth = 0;
        for (ControlTab tab : tabs) {
            labelsWidth += this.font.width(tab.label);
        }
        if (labelsWidth + tabs.length * 2 > availableTabWidth) {
            this.compactTabs = true;
            labelsWidth = 0;
            for (ControlTab tab : tabs) {
                labelsWidth += this.font.width(tab.shortLabel);
            }
        }
        int extraTabWidth = Math.max(0, (availableTabWidth - labelsWidth) / tabs.length);
        int tabX = this.deckX + 7;
        for (ControlTab tab : tabs) {
            String label = this.compactTabs ? tab.shortLabel : tab.label;
            int tabWidth = this.font.width(label) + extraTabWidth;
            this.addRenderableWidget(new TabButton(tabX, this.tabY, tabWidth, TAB_HEIGHT, tab));
            tabX += tabWidth + tabGap;
        }

        int controlsTop = this.bodyTop + 44;
        int controlsBottom = this.bodyBottom - 6;
        int available = Math.max(60, controlsBottom - controlsTop);
        this.deckRowHeight = Math.max(15, Math.min(28,
            (available - ROW_GAP * (CONTROL_ROWS - 1)) / CONTROL_ROWS));
        this.deckRowStart = controlsTop;

        this.buildLensControls();
        this.buildMotionControls();
        this.buildOutputControls();
        this.buildManualControls();
    }

    private void buildLensControls() {
        this.cameraButton = this.addDeckButton(ControlTab.LENS, 0, "Camera", () -> {
            this.camera.toggle();
            this.refreshLabels();
        });
        this.dynamicButton = this.addDeckButton(ControlTab.LENS, 1, "Adaptive", () -> {
            this.camera.toggleDynamic();
            this.refreshLabels();
        });
        this.speedButton = this.addDeckButton(ControlTab.LENS, 2, "Playback", () -> {
            this.camera.cycleSpeed();
            this.refreshLabels();
        });
        this.addDeckSlider(ControlTab.LENS, 3, "FOV", 30.0, 100.0,
            () -> this.camera.config().fov,
            value -> this.camera.config().fov = (int) Math.round(value), "%.0f");
        this.addDeckSlider(ControlTab.LENS, 4, "Distance", 2.0, 16.0,
            () -> this.camera.config().radius,
            value -> this.camera.config().radius = value, "%.1f");
        this.addDeckSlider(ControlTab.LENS, 5, "Height", 0.0, 8.0,
            () -> this.camera.config().height,
            value -> this.camera.config().height = value, "%.1f");
        this.addDeckSlider(ControlTab.LENS, 6, "Aim height", 0.6, 2.4,
            () -> this.camera.config().targetHeight,
            value -> this.camera.config().targetHeight = value, "%.1f");
    }

    private void buildMotionControls() {
        this.addDeckSlider(ControlTab.MOTION, 0, "Wall margin", 0.05, 1.0,
            () -> this.camera.config().cameraMargin,
            value -> this.camera.config().cameraMargin = value, "%.2f");
        this.geometryButton = this.addDeckButton(ControlTab.MOTION, 1, "Wall avoid", () -> {
            CinecamConfig config = this.camera.config();
            config.avoidGeometry = !config.avoidGeometry;
            config.save(CinecamClient.LOGGER);
            this.refreshLabels();
        });
        this.addDeckSlider(ControlTab.MOTION, 2, "Intro", 0.0, 4.0,
            () -> this.camera.config().introSeconds,
            value -> this.camera.config().introSeconds = value, "%.1fs");
        this.addDeckSlider(ControlTab.MOTION, 3, "Shake", 0.0, 0.12,
            () -> this.camera.config().shake,
            value -> this.camera.config().shake = value, "%.3f");
        this.seeThroughButton = this.addDeckButton(ControlTab.MOTION, 4, "See-through", () -> {
            CinecamConfig config = this.camera.config();
            config.seeThroughPlayer = !config.seeThroughPlayer;
            config.save(CinecamClient.LOGGER);
            this.refreshLabels();
        });
    }

    private void buildOutputControls() {
        this.frameButton = this.addDeckButton(ControlTab.OUTPUT, 0, "Frame", () -> {
            this.camera.toggleLetterbox();
            this.refreshLabels();
        });
        this.hudButton = this.addDeckButton(ControlTab.OUTPUT, 1, "HUD", () -> {
            CinecamConfig config = this.camera.config();
            config.hideHud = !config.hideHud;
            config.save(CinecamClient.LOGGER);
            this.refreshLabels();
        });
        this.directorButton = this.addDeckButton(ControlTab.OUTPUT, 2, "Director", () -> {
            this.camera.toggleDirector();
            this.refreshLabels();
        });
    }

    private void buildManualControls() {
        this.manualButton = this.addDeckButton(ControlTab.MANUAL, 0, "Manual", () -> {
            this.camera.toggleManual();
            this.refreshLabels();
        });
        this.manualResetButton = this.addDeckButton(ControlTab.MANUAL, 1, "Center offset", () -> {
            this.camera.resetManualOffset();
            this.refreshLabels();
        });
    }

    private DeckButton addDeckButton(ControlTab page, int row, String label, Runnable action) {
        DeckButton button = new DeckButton(
            this.deckControlX, this.rowY(row), this.deckControlWidth,
            this.deckRowHeight - 2, label, action, false
        );
        this.addRenderableWidget(button);
        this.pageControls.add(new PageControl(page, button));
        return button;
    }

    private void addDeckSlider(ControlTab page, int row, String label, double min, double max,
                               DoubleSupplier getter, DoubleConsumer setter, String format) {
        DeckSlider slider = new DeckSlider(
            this.deckControlX, this.rowY(row), this.deckControlWidth,
            this.deckRowHeight - 2, label, min, max, getter, setter, format
        );
        this.addRenderableWidget(slider);
        this.pageControls.add(new PageControl(page, slider));
    }

    private int rowY(int row) {
        return this.deckRowStart + row * (this.deckRowHeight + ROW_GAP);
    }

    private void applyControlTab() {
        for (PageControl pageControl : this.pageControls) {
            boolean visible = pageControl.tab == this.controlTab;
            pageControl.widget.visible = visible;
            pageControl.widget.active = visible;
        }
    }

    private void selectTab(ControlTab tab) {
        if (this.controlTab == tab) {
            return;
        }
        this.controlTab = tab;
        this.clearFocus();
        this.applyControlTab();
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
        setDeckState(this.cameraButton, "Camera: " + (this.camera.isEnabled() ? "on" : "off"),
            this.camera.isEnabled());
        setDeckState(this.dynamicButton, "Adaptive: " + (this.camera.isDynamicEnabled() ? "on" : "off"),
            this.camera.isDynamicEnabled());
        setDeckState(this.speedButton, "Playback: " + formatSpeed(this.camera.speedFactor()), false);
        setDeckState(this.frameButton, "Frame: " + (config.letterbox ? "2.39:1" : "full"),
            config.letterbox);
        setDeckState(this.hudButton, "HUD: " + (config.hideHud ? "hidden" : "shown"),
            config.hideHud);
        setDeckState(this.directorButton, "Director: " + (this.camera.isDirectorEnabled() ? "on" : "off"),
            this.camera.isDirectorEnabled());
        setDeckState(this.geometryButton, "Wall avoid: " + (config.avoidGeometry ? "on" : "off"),
            config.avoidGeometry);
        setDeckState(this.seeThroughButton, "See-through: " + (config.seeThroughPlayer ? "on" : "off"),
            config.seeThroughPlayer);
        setDeckState(this.manualButton, "Manual: " + (this.camera.isManualEnabled() ? "on" : "off"),
            this.camera.isManualEnabled());
    }

    private static void setDeckState(DeckButton button, String label, boolean highlighted) {
        if (button == null) {
            return;
        }
        button.setLabel(label);
        button.setHighlighted(highlighted);
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
        graphics.fillGradient(0, 0, this.width, this.height, COLOR_INK_TOP, COLOR_INK_BOTTOM);

        drawTopBar(graphics);
        drawFooter(graphics);

        if (this.camera == null) {
            graphics.text(this.font, "Camera unavailable", MARGIN, this.height / 2, COLOR_TALLY, false);
            return;
        }

        drawPanel(graphics, this.leftX, this.bodyTop, this.leftWidth, this.bodyBottom - this.bodyTop);
        drawPanel(graphics, this.monitorX, this.bodyTop, this.monitorWidth, this.bodyBottom - this.bodyTop);
        drawPanel(graphics, this.deckX, this.bodyTop, this.deckWidth, this.bodyBottom - this.bodyTop);

        drawShotRailChrome(graphics);
        drawMonitor(graphics);
        drawDeckChrome(graphics);
        drawManualNotes(graphics);
    }

    private void drawTopBar(GuiGraphicsExtractor graphics) {
        graphics.fill(MARGIN, 7, MARGIN + 2, 19, COLOR_AMBER);
        drawTrackedText(graphics, "Cinecam", MARGIN + 6, 6, COLOR_CHALK, 1);
        graphics.text(this.font, "motion studio", MARGIN + 6, 17, COLOR_DIM, false);

        int closeX = this.width - MARGIN - 46;
        String live = this.camera != null && this.camera.isEnabled() ? "Live" : "Standby";
        String mode = this.camera != null && this.camera.isDynamicEnabled() ? "Adaptive" : "Classic";
        if (this.camera != null && this.camera.isManualEnabled()) {
            mode += " / Manual";
        }
        int liveWidth = this.font.width(live);
        int statusWidth = 9 + liveWidth + 10 + this.font.width(mode);
        int statusX = closeX - 10 - statusWidth;
        int statusY = 7;
        if (statusX > MARGIN + 78) {
            int tallyColor = this.camera != null && this.camera.isEnabled() ? COLOR_TALLY : COLOR_DIM;
            graphics.fill(statusX, statusY + 2, statusX + 4, statusY + 6, tallyColor);
            graphics.text(this.font, live, statusX + 9, statusY,
                this.camera != null && this.camera.isEnabled() ? COLOR_CHALK : COLOR_DIM, false);
            graphics.text(this.font, mode, statusX + 9 + liveWidth + 10, statusY, COLOR_SMOKE, false);
        }

        graphics.fill(MARGIN, HEADER_HEIGHT - 1, this.width - MARGIN, HEADER_HEIGHT, COLOR_RULE);
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        graphics.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height - FOOTER_HEIGHT + 1, COLOR_RULE);
        if (this.camera == null) {
            graphics.text(this.font, "Open the studio with /freecam", MARGIN, this.height - 13, COLOR_DIM, false);
            return;
        }
        Shot shot = this.camera.shot();
        String description = shot.displayName() + ": " + shot.description();
        int hintWidth = this.font.width("Esc closes") + 12;
        int available = this.width - MARGIN * 2 - hintWidth;
        graphics.text(this.font, this.font.plainSubstrByWidth(description, available),
            MARGIN, this.height - 13, COLOR_SMOKE, false);
        graphics.text(this.font, "Esc closes",
            this.width - MARGIN - this.font.width("Esc closes"), this.height - 13, COLOR_DIM, false);
    }

    private void drawShotRailChrome(GuiGraphicsExtractor graphics) {
        graphics.text(this.font, "Shots", this.leftX + 7, this.bodyTop + 7, COLOR_SMOKE, false);
        String count = String.valueOf(Shot.values().length);
        graphics.text(this.font, count,
            this.leftX + this.leftWidth - 7 - this.font.width(count),
            this.bodyTop + 7, COLOR_DIM, false);
        graphics.fill(this.leftX + 7, this.bodyTop + 19,
            this.leftX + this.leftWidth - 7, this.bodyTop + 20, COLOR_RULE_SOFT);
    }

    private void drawDeckChrome(GuiGraphicsExtractor graphics) {
        graphics.text(this.font, "Setup", this.deckX + 7, this.bodyTop + 7, COLOR_SMOKE, false);
        graphics.fill(this.deckX + 7, this.tabY + TAB_HEIGHT + 1,
            this.deckX + this.deckWidth - 7, this.tabY + TAB_HEIGHT + 2, COLOR_RULE_SOFT);
    }

    private void drawManualNotes(GuiGraphicsExtractor graphics) {
        if (this.controlTab != ControlTab.MANUAL) {
            return;
        }
        int x = this.deckControlX + 6;
        int y = this.rowY(2) + 3;
        int textWidth = Math.max(1, this.deckControlWidth - 12);
        int lineStep = this.font.lineHeight + 3;
        graphics.text(this.font, this.font.plainSubstrByWidth("WASD pans", textWidth),
            x, y, COLOR_SMOKE, false);
        graphics.text(this.font, this.font.plainSubstrByWidth("Wheel zooms", textWidth),
            x, y + lineStep, COLOR_SMOKE, false);
        graphics.text(this.font, this.font.plainSubstrByWidth("Player still moves", textWidth),
            x, y + lineStep * 2, COLOR_DIM, false);
    }

    private void drawMonitor(GuiGraphicsExtractor graphics) {
        CinecamConfig config = this.camera.config();
        int x = this.monitorX + 7;
        int y = this.bodyTop + 24;
        int width = this.monitorWidth - 14;
        int height = this.bodyBottom - y - 22;
        if (width < 12 || height < 12) {
            return;
        }

        boolean live = this.camera.isEnabled();
        graphics.text(this.font, "Live view", this.monitorX + 7, this.bodyTop + 7, COLOR_SMOKE, false);
        String tally = live ? "Live" : "Standby";
        int tallyWidth = this.font.width(tally);
        int tallyX = this.monitorX + this.monitorWidth - 7 - tallyWidth;
        graphics.fill(tallyX - 9, this.bodyTop + 10, tallyX - 5, this.bodyTop + 14,
            live ? COLOR_TALLY : COLOR_DIM);
        graphics.text(this.font, tally, tallyX, this.bodyTop + 7,
            live ? COLOR_SMOKE : COLOR_DIM, false);

        graphics.fill(x, y, x + width, y + height, COLOR_PANEL_LOW);
        graphics.outline(x, y, x + width, y + height, COLOR_RULE_SOFT);

        for (int third = 1; third <= 2; third++) {
            int tx = x + width * third / 3;
            int ty = y + height * third / 3;
            graphics.fill(tx, y, tx + 1, y + height, 0x1AFFFFFF);
            graphics.fill(x, ty, x + width, ty + 1, 0x1AFFFFFF);
        }
        drawGate(graphics, x, y, width, height);

        int centerX = x + width / 2;
        int centerY = y + height / 2;
        double radius = Math.min(width, height) * 0.32;
        double period = this.camera.shot() == Shot.FLYBY ? 18.0 : 12.0;
        double time = System.currentTimeMillis() / 1000.0 * 0.55;
        int samples = 56;
        int pathColor = live ? COLOR_PATH : COLOR_PATH_OFF;
        for (int index = 0; index < samples; index++) {
            double[] from = previewPoint(this.camera.shot(), period * index / samples);
            double[] to = previewPoint(this.camera.shot(), period * (index + 1) / samples);
            int fromX = centerX + (int) Math.round(from[0] * radius);
            int fromY = centerY + (int) Math.round(from[1] * radius);
            int toX = centerX + (int) Math.round(to[0] * radius);
            int toY = centerY + (int) Math.round(to[1] * radius);
            drawLine(graphics, fromX + 1, fromY + 1, toX + 1, toY + 1, 0x32000000);
            drawLine(graphics, fromX, fromY, toX, toY, pathColor);
        }

        double[] current = previewPoint(this.camera.shot(), time);
        int cameraX = centerX + (int) Math.round(current[0] * radius);
        int cameraY = centerY + (int) Math.round(current[1] * radius);
        drawLine(graphics, cameraX, cameraY, centerX, centerY, live ? 0x55DDAA55 : 0x33DDAA55);
        graphics.fill(cameraX - 2, cameraY - 2, cameraX + 3, cameraY + 3,
            live ? COLOR_AMBER : COLOR_DIM);

        graphics.fill(centerX - 4, centerY, centerX + 5, centerY + 1, COLOR_CHALK);
        graphics.fill(centerX, centerY - 4, centerX + 1, centerY + 5, COLOR_CHALK);
        graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, COLOR_AMBER);

        String name = this.camera.shot().displayName();
        graphics.text(this.font, name, x, this.bodyBottom - 13, COLOR_CHALK, false);
        String metrics = String.format(Locale.ROOT, "%.0f°  %.1f m",
            this.camera.effectiveFov(), config.radius);
        graphics.text(this.font, metrics,
            x + width - this.font.width(metrics), this.bodyBottom - 13, COLOR_DIM, false);
    }

    private static void drawGate(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        int length = 7;
        graphics.fill(x, y, x + length, y + 1, COLOR_GATE);
        graphics.fill(x, y, x + 1, y + length, COLOR_GATE);
        graphics.fill(x + width - length, y, x + width, y + 1, COLOR_GATE);
        graphics.fill(x + width - 1, y, x + width, y + length, COLOR_GATE);
        graphics.fill(x, y + height - 1, x + length, y + height, COLOR_GATE);
        graphics.fill(x, y + height - length, x + 1, y + height, COLOR_GATE);
        graphics.fill(x + width - length, y + height - 1, x + width, y + height, COLOR_GATE);
        graphics.fill(x + width - 1, y + height - length, x + width, y + height, COLOR_GATE);
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL);
        graphics.outline(x, y, x + width, y + height, COLOR_RULE);
    }

    private void drawTrackedText(GuiGraphicsExtractor graphics, String text, int x, int y,
                                 int color, int tracking) {
        int cursor = x;
        for (int index = 0; index < text.length(); index++) {
            String glyph = String.valueOf(text.charAt(index));
            graphics.text(this.font, glyph, cursor, y, color, false);
            cursor += this.font.width(glyph) + tracking;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(null);
    }

    private enum ControlTab {
        LENS("Lens", "Lens"),
        MOTION("Motion", "Move"),
        OUTPUT("Output", "Out"),
        MANUAL("Manual", "Hand");

        private final String label;
        private final String shortLabel;

        ControlTab(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    private record PageControl(ControlTab tab, AbstractWidget widget) {
    }

    /**
     * A vertical shot index. The rail is deliberately still; the large monitor
     * is the one moving image in the studio.
     */
    private final class ShotRow extends AbstractWidget {
        private static final double GLYPH_TIME = 4.0;
        private final Shot shot;

        private ShotRow(int x, int y, int width, int height, Shot shot) {
            super(x, y, width, height, Component.literal(shot.displayName()));
            this.shot = shot;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (CinecamScreen.this.camera == null) {
                return;
            }
            boolean selected = CinecamScreen.this.camera.shot() == this.shot;
            boolean hot = this.isHoveredOrFocused();
            int x = this.getX();
            int y = this.getY();
            int width = this.width;
            int height = this.height;

            if (selected) {
                graphics.fill(x, y, x + width, y + height, COLOR_SELECT);
                graphics.fill(x, y, x + 2, y + height, COLOR_AMBER);
            } else if (hot) {
                graphics.fill(x, y, x + width, y + height, COLOR_HOVER);
            }
            graphics.fill(x, y + height - 1, x + width, y + height, COLOR_RULE_SOFT);

            int glyphX = x + 5;
            int glyphCenterX = glyphX + 7;
            int glyphCenterY = y + height / 2;
            double glyphRadius = Math.max(4.0, Math.min(7.0, height * 0.32));
            int glyphColor = selected ? 0x99DDAA55 : 0x55667575;
            for (int index = 0; index <= 20; index++) {
                double[] point = previewPoint(this.shot, GLYPH_TIME * index / 20.0);
                int px = glyphCenterX + (int) Math.round(point[0] * glyphRadius);
                int py = glyphCenterY + (int) Math.round(point[1] * glyphRadius);
                graphics.fill(px, py, px + 1, py + 1, glyphColor);
            }
            graphics.fill(glyphCenterX - 1, glyphCenterY - 1,
                glyphCenterX + 2, glyphCenterY + 2, selected ? COLOR_AMBER : COLOR_DIM);

            int textX = x + 25;
            int textWidth = Math.max(1, width - (textX - x) - 5);
            int textY = height >= 28
                ? y + (height - CinecamScreen.this.font.lineHeight * 2 - 2) / 2
                : y + (height - CinecamScreen.this.font.lineHeight) / 2;
            String name = CinecamScreen.this.font.plainSubstrByWidth(this.shot.displayName(), textWidth);
            graphics.text(CinecamScreen.this.font, name, textX, textY,
                selected ? COLOR_CHALK : COLOR_SMOKE, false);
            if (height >= 28) {
                String detail = CinecamScreen.this.font.plainSubstrByWidth(
                    this.shot.description(), textWidth);
                graphics.text(CinecamScreen.this.font, detail, textX,
                    textY + CinecamScreen.this.font.lineHeight + 2, COLOR_DIM, false);
            }
            if (this.isFocused()) {
                graphics.outline(x, y, x + width, y + height, COLOR_SMOKE);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (CinecamScreen.this.camera != null && event.button() == 0) {
                CinecamScreen.this.camera.setShot(this.shot);
                CinecamScreen.this.refreshLabels();
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    private final class TabButton extends AbstractWidget {
        private final ControlTab tab;

        private TabButton(int x, int y, int width, int height, ControlTab tab) {
            super(x, y, width, height, Component.literal(tab.label));
            this.tab = tab;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            boolean selected = CinecamScreen.this.controlTab == this.tab;
            boolean hot = this.isHoveredOrFocused();
            int x = this.getX();
            int y = this.getY();
            int width = this.width;
            int height = this.height;
            if (hot) {
                graphics.fill(x, y, x + width, y + height, COLOR_HOVER);
            }
            if (selected) {
                graphics.fill(x, y + height - 2, x + width, y + height, COLOR_AMBER);
            }
            String tabLabel = CinecamScreen.this.compactTabs
                ? this.tab.shortLabel
                : this.tab.label;
            String label = CinecamScreen.this.font.plainSubstrByWidth(
                tabLabel, Math.max(1, width));
            int textX = x + (width - CinecamScreen.this.font.width(label)) / 2;
            int textY = y + (height - CinecamScreen.this.font.lineHeight) / 2;
            graphics.text(CinecamScreen.this.font, label, textX, textY,
                selected ? COLOR_CHALK : COLOR_SMOKE, false);
            if (this.isFocused()) {
                graphics.outline(x, y, x + width, y + height, COLOR_SMOKE);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0) {
                CinecamScreen.this.selectTab(this.tab);
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    private final class DeckButton extends AbstractWidget {
        private final Runnable action;
        private final boolean centered;
        private boolean highlighted;
        private String label;

        private DeckButton(int x, int y, int width, int height, String label,
                           Runnable action, boolean centered) {
            super(x, y, width, height, Component.literal(label));
            this.label = label;
            this.action = action;
            this.centered = centered;
        }

        private void setLabel(String label) {
            if (this.label.equals(label)) {
                return;
            }
            this.label = label;
            this.setMessage(Component.literal(label));
        }

        private void setHighlighted(boolean highlighted) {
            this.highlighted = highlighted;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            boolean hot = this.isHoveredOrFocused();
            int x = this.getX();
            int y = this.getY();
            int width = this.width;
            int height = this.height;
            if (hot) {
                graphics.fill(x, y, x + width, y + height, COLOR_HOVER);
            }
            if (this.highlighted) {
                graphics.fill(x, y, x + 2, y + height, COLOR_AMBER);
            }
            graphics.fill(x, y + height - 1, x + width, y + height,
                this.highlighted ? COLOR_AMBER_DIM : COLOR_RULE_SOFT);

            String text = CinecamScreen.this.font.plainSubstrByWidth(
                this.label, Math.max(1, width - 10));
            int textX = this.centered ? x + (width - CinecamScreen.this.font.width(text)) / 2 : x + 6;
            int textY = y + (height - CinecamScreen.this.font.lineHeight) / 2;
            graphics.text(CinecamScreen.this.font, text, textX, textY,
                this.highlighted ? COLOR_CHALK : COLOR_SMOKE, false);
            if (this.isFocused()) {
                graphics.outline(x, y, x + width, y + height, COLOR_SMOKE);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0 && this.action != null) {
                this.action.run();
                playButtonClickSound(Minecraft.getInstance().getSoundManager());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    private final class DeckSlider extends AbstractSliderButton {
        private String label;
        private double min;
        private double max;
        private DoubleSupplier getter;
        private DoubleConsumer setter;
        private String format;

        private DeckSlider(int x, int y, int width, int height, String label,
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
            this.setMessage(Component.literal(
                this.label + ": " + String.format(Locale.ROOT, this.format, this.getter.getAsDouble())));
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

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (this.getter == null || this.format == null) {
                return;
            }
            boolean hot = this.isHoveredOrFocused();
            int x = this.getX();
            int y = this.getY();
            int width = this.width;
            int height = this.height;
            if (hot) {
                graphics.fill(x, y, x + width, y + height, COLOR_HOVER);
            }

            String label = CinecamScreen.this.font.plainSubstrByWidth(
                this.label, Math.max(1, width / 2));
            String value = String.format(Locale.ROOT, this.format, this.getter.getAsDouble());
            graphics.text(CinecamScreen.this.font, label, x + 6, y + 2,
                hot ? COLOR_CHALK : COLOR_SMOKE, false);
            graphics.text(CinecamScreen.this.font, value,
                x + width - 6 - CinecamScreen.this.font.width(value), y + 2,
                hot ? COLOR_CHALK : COLOR_DIM, false);

            int trackY = y + height - 4;
            graphics.fill(x + 6, trackY, x + width - 6, trackY + 1, COLOR_RULE);
            int trackWidth = Math.max(1, width - 12);
            int fillWidth = (int) Math.round(trackWidth * this.value);
            if (fillWidth > 0) {
                graphics.fill(x + 6, trackY, x + 6 + fillWidth, trackY + 1, COLOR_AMBER);
            }
            int knobX = x + 6 + Mth.clamp(fillWidth, 0, trackWidth);
            graphics.fill(knobX - 1, trackY - 2, knobX + 2, trackY + 2,
                hot ? COLOR_CHALK : COLOR_SMOKE);
            if (this.isFocused()) {
                graphics.outline(x, y, x + width, y + height, COLOR_SMOKE);
            }
        }
    }

    private static double normalizeValue(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return Mth.clamp((value - min) / (max - min), 0.0, 1.0);
    }

    /**
     * Stylised top-down path used by the rail and monitor. It is deliberately
     * independent of the camera's live rig: the path shows the shape of the
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
                theta = -2.1 + progress * Math.PI * 2.0;
                radius = 1.0 - 0.45 * Math.sin(Math.PI * progress);
                x = Math.cos(theta) * radius;
                y = Math.sin(theta) * radius;
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
}
