package dev.frost.obfuscator.gui.dialog;

import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

public final class DialogService {
    private final Stage owner;
    private final PreferencesStore preferences;

    public DialogService(Stage owner, PreferencesStore preferences) {
        this.owner = owner;
        this.preferences = preferences;
    }

    public Optional<Path> openJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose input JAR");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java archives", "*.jar"));
        restoreDirectory(chooser, "jar");
        return remember(optional(chooser.showOpenDialog(owner)), "jar");
    }

    public Optional<Path> openConfig() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Frostfuscator configuration");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML configuration", "*.yml", "*.yaml"));
        restoreDirectory(chooser, "config");
        return remember(optional(chooser.showOpenDialog(owner)), "config");
    }

    public Optional<Path> saveConfig() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Frostfuscator configuration");
        chooser.setInitialFileName("config.yml");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML configuration", "*.yml", "*.yaml"));
        restoreDirectory(chooser, "config");
        return remember(optional(chooser.showSaveDialog(owner)), "config");
    }

    public Optional<Path> saveJar(String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose protected JAR output");
        chooser.setInitialFileName(suggestedName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java archives", "*.jar"));
        restoreDirectory(chooser, "jar");
        return remember(optional(chooser.showSaveDialog(owner)), "jar");
    }

    public Optional<Path> chooseDirectory(String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        String raw = preferences.get("dialog.directory.folder", "");
        if (!raw.isBlank()) {
            File directory = new File(raw);
            if (directory.isDirectory()) chooser.setInitialDirectory(directory);
        }
        Optional<Path> selected = optional(chooser.showDialog(owner));
        selected.ifPresent(path -> preferences.put("dialog.directory.folder", path.toAbsolutePath().toString()));
        return selected;
    }

    public void error(String title, Throwable throwable) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(throwable.getMessage() == null ? throwable.toString() : throwable.getMessage());
        alert.showAndWait();
    }

    public boolean confirm(String title, String explanation, String action) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(explanation);
        ButtonType confirm = new ButtonType(action);
        alert.getButtonTypes().setAll(ButtonType.CANCEL, confirm);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == confirm;
    }

    private static Optional<Path> optional(File file) {
        return file == null ? Optional.empty() : Optional.of(file.toPath());
    }

    private void restoreDirectory(FileChooser chooser, String kind) {
        String raw = preferences.get("dialog.directory." + kind, "");
        if (raw.isBlank()) return;
        File directory = new File(raw);
        if (directory.isDirectory()) chooser.setInitialDirectory(directory);
    }

    private Optional<Path> remember(Optional<Path> selected, String kind) {
        selected.map(Path::toAbsolutePath).map(Path::getParent).ifPresent(parent ->
                preferences.put("dialog.directory." + kind, parent.toString()));
        return selected;
    }
}
