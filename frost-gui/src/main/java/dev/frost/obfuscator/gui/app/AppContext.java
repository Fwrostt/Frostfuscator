package dev.frost.obfuscator.gui.app;

import dev.frost.obfuscator.gui.analysis.JarAnalyzer;
import dev.frost.obfuscator.gui.analysis.RecommendationEngine;
import dev.frost.obfuscator.gui.build.BuildController;
import dev.frost.obfuscator.gui.config.ConfigurationBinder;
import dev.frost.obfuscator.gui.console.ConsoleModel;
import dev.frost.obfuscator.gui.crypto.FileEncryptionService;
import dev.frost.obfuscator.gui.plugin.PluginRuntimeService;
import dev.frost.obfuscator.gui.jni.NativeToolchainService;
import dev.frost.obfuscator.gui.dialog.DialogService;
import dev.frost.obfuscator.gui.notification.NotificationCenter;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.gui.state.WorkspacePersistence;
import dev.frost.obfuscator.gui.theme.ThemeManager;
import dev.frost.obfuscator.gui.validation.ProjectValidator;
import dev.frost.obfuscator.gui.validation.ValidationCoordinator;
import dev.frost.obfuscator.gui.viewer.BytecodeViewerService;
import javafx.stage.Stage;

import java.util.ArrayList;

public final class AppContext implements AutoCloseable {
    private final Stage stage;
    private final PreferencesStore preferences;
    private final ProjectState projectState;
    private final ThemeManager themeManager;
    private final ConfigurationBinder configurationBinder;
    private final JarAnalyzer jarAnalyzer;
    private final RecommendationEngine recommendationEngine;
    private final ProjectValidator projectValidator;
    private final ValidationCoordinator validationCoordinator;
    private final ConsoleModel consoleModel;
    private final BuildController buildController;
    private final DialogService dialogs;
    private final NotificationCenter notifications;
    private final WorkspacePersistence workspacePersistence;
    private final BytecodeViewerService bytecodeViewerService;
    private final FileEncryptionService fileEncryptionService;
    private final PluginRuntimeService pluginRuntimeService;
    private final NativeToolchainService nativeToolchainService;

    private AppContext(
            Stage stage,
            PreferencesStore preferences,
            ProjectState projectState,
            ThemeManager themeManager,
            ConfigurationBinder configurationBinder,
            JarAnalyzer jarAnalyzer,
            RecommendationEngine recommendationEngine,
            ProjectValidator projectValidator,
            ValidationCoordinator validationCoordinator,
            ConsoleModel consoleModel,
            BuildController buildController,
            DialogService dialogs,
            NotificationCenter notifications,
            WorkspacePersistence workspacePersistence,
            BytecodeViewerService bytecodeViewerService,
            FileEncryptionService fileEncryptionService,
            PluginRuntimeService pluginRuntimeService,
            NativeToolchainService nativeToolchainService
    ) {
        this.stage = stage;
        this.preferences = preferences;
        this.projectState = projectState;
        this.themeManager = themeManager;
        this.configurationBinder = configurationBinder;
        this.jarAnalyzer = jarAnalyzer;
        this.recommendationEngine = recommendationEngine;
        this.projectValidator = projectValidator;
        this.validationCoordinator = validationCoordinator;
        this.consoleModel = consoleModel;
        this.buildController = buildController;
        this.dialogs = dialogs;
        this.notifications = notifications;
        this.workspacePersistence = workspacePersistence;
        this.bytecodeViewerService = bytecodeViewerService;
        this.fileEncryptionService = fileEncryptionService;
        this.pluginRuntimeService = pluginRuntimeService;
        this.nativeToolchainService = nativeToolchainService;
    }

    public static AppContext create(Stage stage, PreferencesStore preferences) {
        return create(stage, preferences, true);
    }

    /**
     * Creates the lightweight service graph used by the normal startup flow.
     * Project paths and session activity deliberately begin empty. Reusable
     * protection configuration is restored asynchronously by the startup flow.
     */
    public static AppContext createForStartup(Stage stage, PreferencesStore preferences) {
        return create(stage, preferences, false);
    }

    private static AppContext create(Stage stage, PreferencesStore preferences, boolean restoreWorkspace) {
        ProjectState state = new ProjectState();
        ConfigurationBinder binder = new ConfigurationBinder(state);
        ThemeManager themes = new ThemeManager(preferences);
        JarAnalyzer analyzer = new JarAnalyzer();
        RecommendationEngine recommendations = new RecommendationEngine();
        ProjectValidator validator = new ProjectValidator(recommendations);
        ConsoleModel console = new ConsoleModel();
        WorkspacePersistence workspace =
                new WorkspacePersistence(preferences.paths(), state, binder, console);
        if (restoreWorkspace) {
            workspace.restore();
            workspace.start();
        } else {
            state.configuration().setInput("");
            state.configuration().setOutput("");
            state.configuration().setLibs("");
            state.configuration().getLibraries().setPaths(new ArrayList<>());
        }
        ValidationCoordinator validation = new ValidationCoordinator(state, validator);
        BuildController builds = new BuildController(state, binder, console, validator, analyzer,
                preferences.paths());
        DialogService dialogs = new DialogService(stage, preferences);
        NotificationCenter notifications = new NotificationCenter();
        BytecodeViewerService viewer = new BytecodeViewerService();
        FileEncryptionService fileEncryption = new FileEncryptionService();
        PluginRuntimeService plugins = new PluginRuntimeService(preferences.paths().root().resolve("plugins"));
        NativeToolchainService toolchains = new NativeToolchainService();
        plugins.scanDefaultDirectory();
        return new AppContext(stage, preferences, state, themes, binder, analyzer, recommendations,
                validator, validation, console, builds, dialogs, notifications, workspace, viewer, fileEncryption,
                plugins, toolchains);
    }

    public Stage stage() { return stage; }
    public PreferencesStore preferences() { return preferences; }
    public ProjectState projectState() { return projectState; }
    public ThemeManager themeManager() { return themeManager; }
    public ConfigurationBinder configurationBinder() { return configurationBinder; }
    public JarAnalyzer jarAnalyzer() { return jarAnalyzer; }
    public RecommendationEngine recommendationEngine() { return recommendationEngine; }
    public ProjectValidator projectValidator() { return projectValidator; }
    public ValidationCoordinator validationCoordinator() { return validationCoordinator; }
    public ConsoleModel consoleModel() { return consoleModel; }
    public BuildController buildController() { return buildController; }
    public DialogService dialogs() { return dialogs; }
    public NotificationCenter notifications() { return notifications; }
    public WorkspacePersistence workspacePersistence() { return workspacePersistence; }
    public BytecodeViewerService bytecodeViewerService() { return bytecodeViewerService; }
    public FileEncryptionService fileEncryptionService() { return fileEncryptionService; }
    public PluginRuntimeService pluginRuntimeService() { return pluginRuntimeService; }
    public NativeToolchainService nativeToolchainService() { return nativeToolchainService; }

    @Override
    public void close() {
        validationCoordinator.close();
        buildController.close();
        workspacePersistence.close();
        bytecodeViewerService.close();
        fileEncryptionService.close();
        pluginRuntimeService.close();
        nativeToolchainService.close();
        preferences.close();
    }
}
