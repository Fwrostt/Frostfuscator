package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.navigation.PageId;

import java.util.function.Consumer;

public final class PageFactory {
    private final AppContext context;
    private final Consumer<PageId> navigation;

    public PageFactory(AppContext context, Consumer<PageId> navigation) {
        this.context = context;
        this.navigation = navigation;
    }

    public PageView create(PageId page) {
        return switch (page) {
            case OVERVIEW -> new OverviewPage(context, navigation);
            case INPUT -> new InputDependenciesPage(context);
            case PROTECTION -> new ProtectionPage(context);
            case RESOURCES -> new ResourcesPage(context);
            case BUILD -> new BuildPage(context);
            case VALIDATION -> new ValidationPage(context, navigation);
            case REPORTS -> new ReportsPage(context);
            case CONSOLE -> new ConsolePage(context);
            case PRESETS -> new PresetsPage(context);
            case SETTINGS -> new SettingsPage(context);
        };
    }
}
