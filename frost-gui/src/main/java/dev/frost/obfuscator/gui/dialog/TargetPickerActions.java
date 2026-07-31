package dev.frost.obfuscator.gui.dialog;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Reusable picker actions for regex rules and FrostJNI target lists. */
public final class TargetPickerActions {
    private TargetPickerActions() {}

    public static Button regexTargets(AppContext context, TextInputControl destination, String title) {
        Button button = Ui.button("Add classes or packages", "secondary-button", () ->
                ArchiveTargetPicker.show(context, title,
                                "Select packages to cover their full hierarchy, or choose individual classes. "
                                        + "Your existing manual regex rules remain editable.",
                                ArchiveTargetPicker.Mode.CLASSES_AND_PACKAGES)
                        .ifPresent(targets -> addLines(destination,
                                targets.stream().map(ArchiveTargetPicker.Target::regexRule).toList())));
        button.setAccessibleText("Add classes or packages to " + title.toLowerCase());
        return button;
    }

    public static Button jniTargets(AppContext context, TextInputControl packages,
                                    TextInputControl classes, String title) {
        Button button = Ui.button("Add classes or packages", "secondary-button", () ->
                ArchiveTargetPicker.show(context, title,
                                "Browse the analyzed JAR hierarchy and select multiple packages or classes.",
                                ArchiveTargetPicker.Mode.CLASSES_AND_PACKAGES)
                        .ifPresent(targets -> {
                            addLines(packages, targets.stream()
                                    .filter(target -> target.kind() == ArchiveTargetPicker.Kind.PACKAGE)
                                    .map(ArchiveTargetPicker.Target::jniValue).toList());
                            addLines(classes, targets.stream()
                                    .filter(target -> target.kind() == ArchiveTargetPicker.Kind.CLASS)
                                    .map(ArchiveTargetPicker.Target::jniValue).toList());
                        }));
        button.setAccessibleText(title);
        return button;
    }

    public static Button jniMethods(AppContext context, TextInputControl methods, String title) {
        Button button = Ui.button("Add methods", "secondary-button", () ->
                ArchiveTargetPicker.show(context, title,
                                "Expand a class, search by method name or descriptor, and select exact overloads.",
                                ArchiveTargetPicker.Mode.METHODS)
                        .ifPresent(targets -> addLines(methods,
                                targets.stream().map(ArchiveTargetPicker.Target::jniValue).toList())));
        button.setAccessibleText(title);
        return button;
    }

    public static List<String> lines(TextInputControl control) {
        if (control == null || control.getText() == null) return List.of();
        return control.getText().lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    public static void addLines(TextInputControl control, Collection<String> additions) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(lines(control));
        additions.stream().map(String::trim).filter(value -> !value.isBlank()).forEach(combined::add);
        control.setText(String.join(System.lineSeparator(), new ArrayList<>(combined)));
    }
}
