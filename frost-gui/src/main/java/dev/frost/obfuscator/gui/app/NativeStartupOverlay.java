package dev.frost.obfuscator.gui.app;

import javafx.stage.Stage;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tiny native startup surface whose animation is independent of the JavaFX
 * application thread. It masks unavoidable JavaFX CSS/layout pulses without
 * changing the final application window or its scene graph.
 */
public final class NativeStartupOverlay implements AutoCloseable {
    private static final boolean SUPPORTED =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final double START_PROGRESS = 0.04;

    private final boolean reducedMotion;
    private final long createdAt = System.nanoTime();
    private final AtomicLong targetBits = new AtomicLong(Double.doubleToRawLongBits(START_PROGRESS));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean dismissRequested = new AtomicBoolean();
    private volatile JWindow window;
    private volatile Surface surface;
    private volatile Timer timer;
    private volatile int x;
    private volatile int y;
    private volatile int width;
    private volatile int height;
    private double displayedProgress = START_PROGRESS;
    private long previousTick;
    private long dismissStarted;

    private NativeStartupOverlay(Stage stage, boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        syncBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    public static NativeStartupOverlay show(Stage stage, boolean reducedMotion) {
        NativeStartupOverlay overlay = new NativeStartupOverlay(stage, reducedMotion);
        if (SUPPORTED) SwingUtilities.invokeLater(overlay::open);
        return overlay;
    }

    public void update(double value) {
        double clamped = Math.max(0, Math.min(1, value));
        targetBits.set(Double.doubleToRawLongBits(clamped));
    }

    public void syncBounds(double x, double y, double width, double height) {
        this.x = (int) Math.round(x);
        this.y = (int) Math.round(y);
        this.width = Math.max(1, (int) Math.round(width));
        this.height = Math.max(1, (int) Math.round(height));
        JWindow current = window;
        if (current != null) SwingUtilities.invokeLater(() ->
                current.setBounds(this.x, this.y, this.width, this.height));
    }

    public void dismiss() {
        dismissRequested.set(true);
    }

    private void open() {
        if (closed.get()) return;
        try {
            JWindow splash = new JWindow();
            splash.setType(Window.Type.UTILITY);
            splash.setBackground(new Color(0, 0, 0, 0));
            splash.setFocusableWindowState(false);
            splash.setAlwaysOnTop(true);
            splash.setBounds(x, y, width, height);
            Surface canvas = new Surface();
            splash.setContentPane(canvas);
            window = splash;
            surface = canvas;

            Timer animation = new Timer(16, event -> tick());
            animation.setCoalesce(true);
            timer = animation;
            previousTick = System.nanoTime();
            animation.start();
            splash.setVisible(true);
        } catch (RuntimeException ignored) {
            disposeOnEdt();
        }
    }

    private void tick() {
        if (closed.get()) {
            disposeOnEdt();
            return;
        }

        long now = System.nanoTime();
        double seconds = Math.min(0.05, (now - previousTick) / 1_000_000_000d);
        previousTick = now;
        double target = Double.longBitsToDouble(targetBits.get());
        displayedProgress += Math.min(Math.max(0, target - displayedProgress), 0.52 * seconds);

        Surface canvas = surface;
        if (dismissRequested.get()) {
            if (dismissStarted == 0) dismissStarted = now;
            double fade = (now - dismissStarted) / 280_000_000d;
            if (canvas != null) canvas.opacity = (float) Math.max(0, 1 - fade);
            if (fade >= 1) {
                close();
                return;
            }
        }
        if (canvas != null) canvas.repaint();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (SwingUtilities.isEventDispatchThread()) disposeOnEdt();
        else SwingUtilities.invokeLater(this::disposeOnEdt);
    }

    private void disposeOnEdt() {
        Timer animation = timer;
        if (animation != null) animation.stop();
        JWindow splash = window;
        if (splash != null) {
            splash.setVisible(false);
            splash.dispose();
        }
        timer = null;
        surface = null;
        window = null;
    }

    private final class Surface extends JComponent {
        private float opacity = 1;

        private Surface() {
            setOpaque(false);
            setDoubleBuffered(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setComposite(AlphaComposite.SrcOver.derive(opacity));

                float w = getWidth();
                float h = getHeight();
                RoundRectangle2D frame = new RoundRectangle2D.Float(0, 0, w, h, 24, 24);
                g.setColor(new Color(2, 4, 5));
                g.fill(frame);

                float glowX = w * 0.42f;
                float glowY = h * 0.44f;
                float glowRadius = Math.max(1, Math.min(w, h) * 0.62f);
                g.setPaint(new RadialGradientPaint(
                        new Point2D.Float(glowX, glowY), glowRadius,
                        new float[]{0, 0.48f, 1},
                        new Color[]{
                                new Color(51, 90, 106, 30),
                                new Color(51, 90, 106, 12),
                                new Color(51, 90, 106, 0)
                        }));
                g.fill(frame);

                int centerX = Math.round(w / 2);
                int centerY = Math.round(h / 2 - 38);
                paintEmblem(g, centerX, centerY);
                paintName(g, centerX, centerY + 194);
                paintProgress(g, centerX, centerY + 222);
            } finally {
                g.dispose();
            }
        }

        private void paintEmblem(Graphics2D g, int centerX, int centerY) {
            int radius = 88;
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(82, 97, 104, 61));
            g.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

            double elapsedSeconds = (System.nanoTime() - createdAt) / 1_000_000_000d;
            double speed = reducedMotion ? 12 : 68;
            int angle = (int) Math.round(-90 - (elapsedSeconds * speed));
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(118, 148, 160, 209));
            g.drawArc(centerX - radius, centerY - radius, radius * 2, radius * 2, angle, 69);

            int plate = 92;
            RoundRectangle2D core = new RoundRectangle2D.Float(
                    centerX - plate / 2f, centerY - plate / 2f, plate, plate, 22, 22);
            g.setColor(new Color(11, 15, 17));
            g.fill(core);
            g.setStroke(new BasicStroke(1));
            g.setColor(new Color(99, 116, 123, 76));
            g.draw(core);

            g.setColor(new Color(111, 152, 169));
            g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(centerX - 17, centerY + 11, centerX - 3, centerY - 13);
            g.drawLine(centerX + 2, centerY + 15, centerX + 16, centerY - 9);
        }

        private void paintName(Graphics2D g, int centerX, int baselineY) {
            Font font = new Font("Inter", Font.PLAIN, 16);
            g.setFont(font);
            g.setColor(new Color(201, 208, 211));
            FontMetrics metrics = g.getFontMetrics(font);
            String name = "Frostfuscator";
            g.drawString(name, centerX - metrics.stringWidth(name) / 2, baselineY);
        }

        private void paintProgress(Graphics2D g, int centerX, int y) {
            int barWidth = 420;
            int barHeight = 5;
            int left = centerX - barWidth / 2;
            RoundRectangle2D track = new RoundRectangle2D.Float(left, y, barWidth, barHeight, 5, 5);
            g.setColor(new Color(19, 24, 26));
            g.fill(track);

            int fillWidth = Math.max(barHeight, (int) Math.round(barWidth * displayedProgress));
            RoundRectangle2D fill = new RoundRectangle2D.Float(left, y, fillWidth, barHeight, 5, 5);
            g.setColor(new Color(105, 139, 153));
            g.fill(fill);
        }
    }
}
