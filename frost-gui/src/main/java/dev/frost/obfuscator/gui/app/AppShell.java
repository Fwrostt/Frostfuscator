package dev.frost.obfuscator.gui.app;

import dev.frost.obfuscator.gui.motion.Motion;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.navigation.Sidebar;
import dev.frost.obfuscator.gui.page.PageFactory;
import dev.frost.obfuscator.gui.page.PageView;
import dev.frost.obfuscator.gui.titlebar.CustomTitleBar;
import javafx.scene.Node;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;

import java.util.EnumMap;
import java.util.Map;

public final class AppShell {
    private final AppContext context;
    private final StackPane root = new StackPane();
    private final StackPane content = new StackPane();
    private final VBox frame;
    private final Rectangle frameClip = new Rectangle();
    private final Sidebar sidebar;
    private final PageFactory pages;
    private final Motion motion;
    private final Map<PageId, PageView> cache = new EnumMap<>(PageId.class);
    private PageId active;

    public AppShell(AppContext context) {
        this.context = context;
        this.motion = new Motion(context.themeManager());
        this.pages = new PageFactory(context, this::navigate);
        this.sidebar = new Sidebar(context.preferences(), context.themeManager(), this::navigate);

        CustomTitleBar titleBar = new CustomTitleBar(context, this::loadConfig, this::saveConfig);
        titleBar.setMinHeight(48);
        titleBar.setPrefHeight(48);
        titleBar.setMaxHeight(48);
        HBox body = new HBox(sidebar, content);
        body.setMinSize(0, 0);
        content.setMinSize(0, 0);
        HBox.setHgrow(content, Priority.ALWAYS);
        content.getStyleClass().add("content-host");
        frame = new VBox(titleBar, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        frame.getStyleClass().addAll("app-root", "window-frame");
        frameClip.widthProperty().bind(frame.widthProperty());
        frameClip.heightProperty().bind(frame.heightProperty());
        root.getStyleClass().add("window-root");
        root.getChildren().addAll(frame, context.notifications().overlay());
        setRoundedFrame(true);
    }

    public StackPane root() { return root; }

    public void setRoundedFrame(boolean rounded) {
        if (rounded) {
            frameClip.setArcWidth(24);
            frameClip.setArcHeight(24);
            frame.setClip(frameClip);
        } else {
            frame.setClip(null);
        }
    }

    public void showInitialPage() {
        String requested = System.getProperty("frost.gui.startPage",
                context.preferences().get("navigation.lastPage", PageId.OVERVIEW.name()));
        try {
            navigate(PageId.valueOf(requested.toUpperCase(java.util.Locale.ROOT)), true);
        } catch (IllegalArgumentException exception) {
            navigate(PageId.OVERVIEW, true);
        }
    }

    public void navigate(PageId page) {
        navigate(page, false);
    }

    private void navigate(PageId page, boolean immediate) {
        PageView cached = cache.get(page);
        if (page == active && cached != null && content.getChildren().contains(cached.root())) return;
        PageId previousPage = active;
        active = page;
        context.preferences().put("navigation.lastPage", page.name());
        PageView view = cache.computeIfAbsent(page, pages::create);
        Node node = view.root();
        Node previous = content.getChildren().isEmpty() ? null : content.getChildren().getLast();
        if (previousPage != null && previousPage != page) {
            PageView previousView = cache.get(previousPage);
            if (previousView != null) previousView.onHidden();
        }
        sidebar.select(page);
        view.onShown();
        if (immediate) {
            content.getChildren().setAll(node);
            node.setOpacity(1);
            node.setTranslateX(0);
            node.setTranslateY(0);
        } else {
            motion.swap(content, previous, node);
        }
    }

    /**
     * Builds and CSS/layout warms a page while the startup surface is covering
     * the shell. The returned duration is used for lightweight startup metrics.
     */
    public long preload(PageId page) {
        long started = System.nanoTime();
        PageView view = cache.computeIfAbsent(page, pages::create);
        Node node = view.root();
        // Virtualized controls and editable spinners maintain internal child
        // lists across pulses. Attaching and detaching them inside one
        // synchronous warm-up pass can race JavaFX cached-bounds updates.
        // Construct these pages now and let their first real attachment layout.
        if (page == PageId.REPORTS || page == PageId.RESOURCES || page == PageId.ENCRYPTOR) {
            return (System.nanoTime() - started) / 1_000_000L;
        }
        content.getChildren().setAll(node);
        root.applyCss();
        root.layout();
        node.setOpacity(1);
        node.setTranslateX(0);
        node.setTranslateY(0);
        return (System.nanoTime() - started) / 1_000_000L;
    }

    /**
     * Constructs and caches a page without forcing a full scene CSS/layout pass.
     * Startup uses this path so every page is ready while animations retain an
     * opportunity to render between page constructions.
     */
    public long preloadPage(PageId page) {
        long started = System.nanoTime();
        cache.computeIfAbsent(page, pages::create);
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public int preloadedPageCount() {
        return cache.size();
    }

    private void loadConfig() {
        context.dialogs().openConfig().ifPresent(path -> {
            try {
                context.configurationBinder().load(path);
                rebuildPageCache();
                context.validationCoordinator().validateNow();
                navigate(PageId.OVERVIEW);
                context.preferences().put("config.lastPath", path.toAbsolutePath().toString());
                context.notifications().show("Configuration loaded");
            } catch (Exception exception) {
                context.dialogs().error("Could not load configuration", exception);
            }
        });
    }

    private void saveConfig() {
        context.dialogs().saveConfig().ifPresent(path -> {
            try {
                context.configurationBinder().save(path);
                context.preferences().put("config.lastPath", path.toAbsolutePath().toString());
                context.notifications().show("Configuration saved");
            } catch (Exception exception) {
                context.dialogs().error("Could not save configuration", exception);
            }
        });
    }

    private void rebuildPageCache() {
        cache.clear();
        active = null;
        for (PageId page : PageId.values()) cache.put(page, pages.create(page));
    }
}
