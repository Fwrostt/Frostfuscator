package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.crypto.PasswordFileCipher;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.crypto.FileEncryptionService;
import dev.frost.obfuscator.gui.motion.SmoothScroll;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class EncryptorPage implements PageView {
    private final AppContext context;
    private final VBox content = new VBox(Ui.SPACE_8);
    private final ScrollPane root = Ui.pageScroll(content);
    private final BooleanProperty decryptMode = new SimpleBooleanProperty(false);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private final TextField input = new TextField();
    private final TextField output = new TextField();
    private final PasswordField password = new PasswordField();
    private final PasswordField confirmation = new PasswordField();
    private final Label passwordHint = Ui.label("Use a unique password with at least 12 characters.", "encryptor-hint");
    private final Label status = Ui.label("Ready", "encryptor-status-copy");
    private final Label resultPath = Ui.label("", "encryptor-result-path");
    private final FontIcon statusIcon = new FontIcon("fth-shield");
    private final ProgressBar progress = new ProgressBar(0);
    private final Button run = Ui.button("Encrypt file", "primary-button", this::runOperation);

    public EncryptorPage(AppContext context) {
        this.context = context;
        build();
    }

    private void build() {
        SmoothScroll.install(root, context.themeManager());
        root.setMaxWidth(Double.MAX_VALUE);
        content.setMaxWidth(Double.MAX_VALUE);
        content.getStyleClass().addAll("page", "encryptor-page");
        content.setPadding(Ui.pageInsets());

        VBox header = Ui.pageHeader("Encryptor", "Encrypt any file with a password, or restore a Frost encrypted file.");
        header.getStyleClass().add("encryptor-page-header");

        ToggleButton encrypt = modeButton("Encrypt", "fth-lock");
        ToggleButton decrypt = modeButton("Decrypt", "fth-unlock");
        ToggleGroup modes = new ToggleGroup();
        encrypt.setToggleGroup(modes);
        decrypt.setToggleGroup(modes);
        encrypt.setSelected(true);
        modes.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                (decryptMode.get() ? decrypt : encrypt).setSelected(true);
                return;
            }
            decryptMode.set(selected == decrypt);
            applyMode();
        });
        HBox modeBar = new HBox(4, encrypt, decrypt);
        modeBar.getStyleClass().add("encryptor-mode-bar");
        modeBar.setAlignment(Pos.CENTER_LEFT);

        input.getStyleClass().add("text-input");
        input.setPromptText("Choose any file");
        output.getStyleClass().add("text-input");
        output.setPromptText("Choose where to save the result");
        password.getStyleClass().add("text-input");
        password.setPromptText("Password");
        confirmation.getStyleClass().add("text-input");
        confirmation.setPromptText("Repeat password");

        Button browseInput = Ui.button("Browse", "secondary-button", this::chooseInput);
        browseInput.setGraphic(new FontIcon("fth-folder"));
        Button browseOutput = Ui.button("Save as", "secondary-button", this::chooseOutput);
        browseOutput.setGraphic(new FontIcon("fth-save"));

        HBox inputRow = fieldWithButton(input, browseInput);
        HBox outputRow = fieldWithButton(output, browseOutput);
        VBox fileFields = new VBox(Ui.SPACE_3,
                fieldLabel("Source file"), inputRow,
                fieldLabel("Output file"), outputRow);

        VBox passwordFields = new VBox(Ui.SPACE_3,
                fieldLabel("Password"), password,
                fieldLabel("Confirm password"), confirmation,
                passwordHint);

        run.setGraphic(new FontIcon("fth-lock"));
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        progress.getStyleClass().add("encryptor-progress");

        statusIcon.getStyleClass().add("encryptor-status-icon");
        VBox statusText = new VBox(Ui.SPACE_1, status, resultPath);
        HBox statusBand = new HBox(Ui.SPACE_3, statusIcon, statusText, Ui.spacer(), run);
        statusBand.setAlignment(Pos.CENTER_LEFT);
        statusBand.getStyleClass().add("encryptor-status-band");

        VBox operation = Ui.section("Protect a file",
                "Files are streamed through AES-256-GCM and written atomically. The original file is never changed.",
                modeBar, fileFields, passwordFields, progress, statusBand);
        operation.getStyleClass().add("encryptor-operation");
        operation.setMinWidth(0);
        operation.setMaxWidth(Double.MAX_VALUE);

        VBox security = securityPanel();
        GridPane workbench = new GridPane();
        workbench.getStyleClass().add("encryptor-workbench");
        workbench.setHgap(Ui.SPACE_6);
        workbench.setVgap(Ui.SPACE_6);
        workbench.setAlignment(Pos.TOP_LEFT);
        workbench.setMaxWidth(Double.MAX_VALUE);
        arrangeWorkbench(workbench, operation, security, false);
        root.viewportBoundsProperty().addListener((obs, old, bounds) ->
                arrangeWorkbench(workbench, operation, security,
                        bounds.getWidth() > 0 && bounds.getWidth() < 900));

        content.getChildren().addAll(header, workbench);

        input.textProperty().addListener((obs, old, value) -> {
            suggestOutput(value);
            validate();
        });
        output.textProperty().addListener((obs, old, value) -> validate());
        password.textProperty().addListener((obs, old, value) -> {
            updatePasswordHint(value);
            validate();
        });
        confirmation.textProperty().addListener((obs, old, value) -> validate());
        decryptMode.addListener((obs, old, value) -> validate());
        busy.addListener((obs, old, value) -> validate());

        installDropTarget();
        applyMode();
    }

    private VBox securityPanel() {
        Label title = Ui.label("Built for sensitive files", "section-title");
        Label copy = Ui.label("The encrypted container includes everything needed to verify and restore the payload — except your password.",
                "section-description");
        copy.setWrapText(true);

        VBox list = new VBox(Ui.SPACE_4,
                securityFact("fth-shield", "Authenticated encryption", "AES-256-GCM detects a wrong password or modified data."),
                securityFact("fth-key", "Hardened passwords", "PBKDF2-HMAC-SHA256 uses a fresh salt and 310,000 rounds."),
                securityFact("fth-file", "Any file type", "JARs, archives, documents, media, and binaries are handled as-is."),
                securityFact("fth-hard-drive", "Safe writes", "A result replaces its destination only after the operation succeeds."));

        VBox panel = new VBox(Ui.SPACE_4, title, copy, list);
        panel.getStyleClass().addAll("section", "encryptor-security");
        panel.setMinWidth(0);
        panel.setMaxWidth(Double.MAX_VALUE);
        return panel;
    }

    private static Node securityFact(String iconName, String title, String copy) {
        FontIcon icon = new FontIcon(iconName);
        icon.getStyleClass().add("encryptor-security-icon");
        Label heading = Ui.label(title, "encryptor-security-title");
        Label description = Ui.label(copy, "encryptor-security-copy");
        description.setWrapText(true);
        VBox text = new VBox(Ui.SPACE_1, heading, description);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(Ui.SPACE_3, icon, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static void arrangeWorkbench(GridPane grid, VBox operation, VBox security, boolean compact) {
        Object current = grid.getProperties().get("compact");
        if (current instanceof Boolean value && value == compact && !grid.getChildren().isEmpty()) return;
        grid.getProperties().put("compact", compact);
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        if (compact) {
            ColumnConstraints full = new ColumnConstraints();
            full.setMinWidth(0);
            full.setHgrow(Priority.ALWAYS);
            full.setFillWidth(true);
            grid.getColumnConstraints().add(full);
            grid.add(operation, 0, 0);
            grid.add(security, 0, 1);
        } else {
            ColumnConstraints primary = new ColumnConstraints(0, 720, Double.MAX_VALUE);
            primary.setHgrow(Priority.ALWAYS);
            primary.setFillWidth(true);
            ColumnConstraints secondary = new ColumnConstraints(280, 340, 380);
            secondary.setHgrow(Priority.NEVER);
            secondary.setFillWidth(true);
            grid.getColumnConstraints().addAll(primary, secondary);
            grid.add(operation, 0, 0);
            grid.add(security, 1, 0);
        }
        GridPane.setHgrow(operation, Priority.ALWAYS);
        GridPane.setHgrow(security, Priority.ALWAYS);
        GridPane.setFillWidth(operation, true);
        GridPane.setFillWidth(security, true);
    }

    private ToggleButton modeButton(String text, String iconName) {
        ToggleButton button = new ToggleButton(text);
        button.setGraphic(new FontIcon(iconName));
        button.getStyleClass().add("encryptor-mode-button");
        return button;
    }

    private static HBox fieldWithButton(TextField field, Button button) {
        HBox row = new HBox(Ui.SPACE_2, field, button);
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static Label fieldLabel(String text) {
        return Ui.label(text, "field-label", "encryptor-field-label");
    }

    private void applyMode() {
        boolean decrypting = decryptMode.get();
        confirmation.setVisible(!decrypting);
        confirmation.setManaged(!decrypting);
        Node confirmationLabel = confirmation.getParent() instanceof Pane parent
                ? parent.getChildren().get(parent.getChildren().indexOf(confirmation) - 1) : null;
        if (confirmationLabel != null) {
            confirmationLabel.setVisible(!decrypting);
            confirmationLabel.setManaged(!decrypting);
        }
        run.setText(decrypting ? "Decrypt file" : "Encrypt file");
        run.setGraphic(new FontIcon(decrypting ? "fth-unlock" : "fth-lock"));
        status.setText(decrypting ? "Ready to decrypt" : "Ready to encrypt");
        resultPath.setText("");
        output.clear();
        suggestOutput(input.getText());
        updatePasswordHint(password.getText());
        validate();
    }

    private void chooseInput() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(decryptMode.get() ? "Choose Frost encrypted file" : "Choose file to encrypt");
        if (decryptMode.get()) {
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Frost encrypted files", "*" + PasswordFileCipher.FILE_EXTENSION, "*.enc"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
        } else {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        }
        File selected = chooser.showOpenDialog(context.stage());
        if (selected != null) input.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    private void chooseOutput() {
        Path inputPath = path(input.getText());
        FileChooser chooser = new FileChooser();
        chooser.setTitle(decryptMode.get() ? "Save decrypted file" : "Save encrypted file");
        chooser.setInitialFileName(suggestedName(inputPath));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        if (inputPath != null && inputPath.getParent() != null && Files.isDirectory(inputPath.getParent())) {
            chooser.setInitialDirectory(inputPath.getParent().toFile());
        }
        File selected = chooser.showSaveDialog(context.stage());
        if (selected != null) output.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    private void runOperation() {
        Path source = path(input.getText());
        Path destination = path(output.getText());
        String validation = validationMessage(source, destination);
        if (validation != null) {
            status.setText(validation);
            return;
        }
        if (Files.exists(destination) && !context.dialogs().confirm("Replace existing file?",
                destination.getFileName() + " already exists. The successful result will replace it.", "Replace")) {
            return;
        }

        char[] secret = password.getText().toCharArray();
        busy.set(true);
        progress.setProgress(0);
        progress.setVisible(true);
        progress.setManaged(true);
        status.setText(decryptMode.get() ? "Decrypting…" : "Encrypting…");
        resultPath.setText(source.getFileName() + " → " + destination.getFileName());

        java.util.function.DoubleConsumer update = value -> Platform.runLater(() -> progress.setProgress(value));
        var future = decryptMode.get()
                ? context.fileEncryptionService().decrypt(source, destination, secret, update)
                : context.fileEncryptionService().encrypt(source, destination, secret, update);
        Arrays.fill(secret, '\0');

        future.whenComplete((result, error) -> Platform.runLater(() -> {
            busy.set(false);
            progress.setVisible(false);
            progress.setManaged(false);
            password.clear();
            confirmation.clear();
            if (error != null) {
                Throwable cause = unwrap(error);
                statusIcon.setIconLiteral("fth-alert-circle");
                status.getStyleClass().setAll("encryptor-status-copy", "encryptor-status-error");
                status.setText(cause.getMessage() == null ? "Operation failed" : cause.getMessage());
                resultPath.setText("No output was written.");
                return;
            }
            statusIcon.setIconLiteral("fth-check-circle");
            status.getStyleClass().setAll("encryptor-status-copy", "encryptor-status-success");
            status.setText(result.encrypted() ? "File encrypted" : "File decrypted");
            resultPath.setText(formatBytes(result.outputBytes()) + " written in "
                    + result.elapsed().toMillis() + " ms · " + result.output());
            context.notifications().show(result.encrypted() ? "Encrypted file created" : "File decrypted successfully");
        }));
    }

    private void validate() {
        Path source = path(input.getText());
        Path destination = path(output.getText());
        String message = validationMessage(source, destination);
        run.setDisable(busy.get() || message != null);
        if (!busy.get() && message != null) {
            statusIcon.setIconLiteral("fth-shield");
            status.getStyleClass().setAll("encryptor-status-copy");
            status.setText(message);
        }
    }

    private String validationMessage(Path source, Path destination) {
        if (source == null || !Files.isRegularFile(source)) return "Choose a source file";
        if (destination == null) return "Choose an output file";
        if (source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
            return "Output must be different from the source";
        }
        if (password.getText().isEmpty()) return "Enter a password";
        if (!decryptMode.get() && !password.getText().equals(confirmation.getText())) return "Passwords do not match";
        return null;
    }

    private void suggestOutput(String value) {
        Path source = path(value);
        if (source == null || source.getParent() == null) return;
        String current = output.getText();
        if (!current.isBlank() && !looksSuggested(current)) return;
        output.setText(source.resolveSibling(suggestedName(source)).toString());
    }

    private String suggestedName(Path source) {
        String name = source == null || source.getFileName() == null ? "output" : source.getFileName().toString();
        if (!decryptMode.get()) return name + PasswordFileCipher.FILE_EXTENSION;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(PasswordFileCipher.FILE_EXTENSION)) {
            return name.substring(0, name.length() - PasswordFileCipher.FILE_EXTENSION.length());
        }
        if (lower.endsWith(".enc")) return name.substring(0, name.length() - 4);
        return name + ".decrypted";
    }

    private boolean looksSuggested(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(PasswordFileCipher.FILE_EXTENSION) || lower.endsWith(".decrypted") || lower.endsWith(".enc");
    }

    private void updatePasswordHint(String value) {
        if (decryptMode.get()) {
            passwordHint.setText("The correct password is required to authenticate and restore the file.");
        } else if (value == null || value.isEmpty()) {
            passwordHint.setText("Use a unique password with at least 12 characters.");
        } else if (value.length() < 8) {
            passwordHint.setText("Weak password — add more characters.");
        } else if (value.length() < 12) {
            passwordHint.setText("Good start — 12+ characters is safer.");
        } else {
            passwordHint.setText("Strong length. Keep this password somewhere safe.");
        }
    }

    private void installDropTarget() {
        content.setOnDragOver(event -> {
            if (event.getGestureSource() != content && event.getDragboard().hasFiles()
                    && event.getDragboard().getFiles().size() == 1) {
                event.acceptTransferModes(TransferMode.COPY);
                content.getStyleClass().add("encryptor-drop-active");
            }
            event.consume();
        });
        content.setOnDragExited(event -> content.getStyleClass().remove("encryptor-drop-active"));
        content.setOnDragDropped(event -> handleDrop(event));
    }

    private void handleDrop(DragEvent event) {
        content.getStyleClass().remove("encryptor-drop-active");
        boolean accepted = event.getDragboard().hasFiles() && event.getDragboard().getFiles().size() == 1;
        if (accepted) input.setText(event.getDragboard().getFiles().getFirst().toPath().toAbsolutePath().toString());
        event.setDropCompleted(accepted);
        event.consume();
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public Node root() {
        return root;
    }
}
