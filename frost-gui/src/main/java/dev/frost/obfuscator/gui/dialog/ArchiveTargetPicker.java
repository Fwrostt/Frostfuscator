package dev.frost.obfuscator.gui.dialog;

import dev.frost.obfuscator.gui.analysis.BytecodeInventory;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.Ui;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.*;
import java.util.regex.Pattern;

/** Searchable multi-select hierarchy backed by the current JAR analysis inventory. */
public final class ArchiveTargetPicker {
    public enum Mode { CLASSES_AND_PACKAGES, METHODS }
    public enum Kind { PACKAGE, CLASS, METHOD, INFO }

    public record Target(Kind kind, String packageName, String className,
                         String methodName, String descriptor, String label) {
        public static Target packageTarget(String packageName) {
            String label = packageName.isBlank() ? "Default package"
                    : packageName.substring(packageName.lastIndexOf('.') + 1);
            return new Target(Kind.PACKAGE, packageName, "", "", "", label);
        }

        public static Target classTarget(String className) {
            int split = className.lastIndexOf('.');
            String packageName = split < 0 ? "" : className.substring(0, split);
            String label = split < 0 ? className : className.substring(split + 1);
            return new Target(Kind.CLASS, packageName, className, "", "", label);
        }

        public static Target methodTarget(String owner, String name, String descriptor) {
            int split = owner.lastIndexOf('.');
            String packageName = split < 0 ? "" : owner.substring(0, split);
            return new Target(Kind.METHOD, packageName, owner, name, descriptor,
                    name + descriptor);
        }

        private static Target info(String label) {
            return new Target(Kind.INFO, "", "", "", "", label);
        }

        public String key() {
            return switch (kind) {
                case PACKAGE -> "package:" + packageName;
                case CLASS -> "class:" + className;
                case METHOD -> "method:" + className + "#" + methodName + descriptor;
                case INFO -> "info:" + label;
            };
        }

        public String searchableText() {
            return String.join(" ", packageName, className, methodName, descriptor, label)
                    .toLowerCase(Locale.ROOT);
        }

        public String regexRule() {
            return switch (kind) {
                case PACKAGE -> packageName.isBlank()
                        ? "^[^.]+$"
                        : "^" + Pattern.quote(packageName) + "(?:\\..*)?$";
                case CLASS -> "^" + Pattern.quote(className) + "$";
                default -> throw new IllegalStateException("Only packages and classes can become class rules");
            };
        }

        public String jniValue() {
            return switch (kind) {
                case PACKAGE -> packageName;
                case CLASS -> className;
                case METHOD -> className + "#" + methodName + descriptor;
                case INFO -> "";
            };
        }
    }

    private final AppContext context;
    private final Mode mode;
    private final BytecodeInventory inventory;
    private final Dialog<List<Target>> dialog = new Dialog<>();
    private final TreeView<Target> tree = new TreeView<>();
    private final TextField search = new TextField();
    private final Label selectionSummary = Ui.label("No targets selected", "setting-description");
    private final ObservableSet<String> selectedKeys = FXCollections.observableSet(new LinkedHashSet<>());
    private final Map<String, Target> targetsByKey = new LinkedHashMap<>();
    private final Map<String, List<BytecodeInventory.MethodInsight>> methodsByOwner = new TreeMap<>();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(160));

    private ArchiveTargetPicker(AppContext context, String title, String explanation, Mode mode) {
        this.context = Objects.requireNonNull(context, "context");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.inventory = context.projectState().analysis().inventory();
        indexTargets();
        configureDialog(title, explanation);
    }

    public static Optional<List<Target>> show(AppContext context, String title,
                                               String explanation, Mode mode) {
        ArchiveTargetPicker picker = new ArchiveTargetPicker(context, title, explanation, mode);
        return picker.dialog.showAndWait();
    }

    private void configureDialog(String title, String explanation) {
        dialog.initOwner(context.stage());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("target-picker");
        var stylesheet = ArchiveTargetPicker.class.getResource("/target-picker.css");
        if (stylesheet != null) pane.getStylesheets().add(stylesheet.toExternalForm());
        Parent applicationRoot = context.stage().getScene() == null ? null : context.stage().getScene().getRoot();
        if (applicationRoot != null) {
            pane.setStyle(applicationRoot.getStyle());
            applicationRoot.getStyleClass().stream()
                    .filter(style -> style.startsWith("density-"))
                    .forEach(style -> pane.getStyleClass().add(style));
        }

        ButtonType add = new ButtonType("Add selected", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, add);
        Node addButton = pane.lookupButton(add);
        addButton.disableProperty().bind(Bindings.isEmpty(selectedKeys));

        Label heading = Ui.label(title, "section-title");
        Label copy = Ui.label(explanation, "section-description");
        copy.setWrapText(true);
        copy.setMaxWidth(720);
        search.getStyleClass().add("text-input");
        search.setPromptText(mode == Mode.METHODS
                ? "Search classes, methods, or descriptors"
                : "Search packages or classes");
        search.setAccessibleText(search.getPromptText());

        tree.setShowRoot(false);
        tree.getStyleClass().add("target-picker-tree");
        tree.setCellFactory(CheckBoxTreeCell.forTreeView(item ->
                        item instanceof CheckBoxTreeItem<Target> checkItem
                                ? checkItem.selectedProperty() : null,
                new StringConverter<>() {
                    @Override public String toString(TreeItem<Target> item) {
                        return item == null || item.getValue() == null ? "" : item.getValue().label();
                    }
                    @Override public TreeItem<Target> fromString(String value) { return null; }
                }));
        tree.setPrefHeight(470);
        tree.setMinHeight(280);
        VBox.setVgrow(tree, Priority.ALWAYS);

        selectedKeys.addListener((javafx.collections.SetChangeListener<String>) change -> updateSummary());
        search.textProperty().addListener((obs, old, value) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(event -> rebuildTree(search.getText()));
        VBox content = new VBox(Ui.SPACE_3, heading, copy, search, tree, selectionSummary);
        content.setMinWidth(Region.USE_PREF_SIZE);
        content.setPrefWidth(720);
        content.setPrefHeight(570);
        pane.setContent(content);
        dialog.setResultConverter(button -> button == add ? selectedTargets() : null);
        rebuildTree("");
        dialog.setOnShown(event -> search.requestFocus());
    }

    private void indexTargets() {
        for (BytecodeInventory.ClassInsight insight : inventory.classes()) {
            Target classTarget = Target.classTarget(insight.name());
            targetsByKey.put(classTarget.key(), classTarget);
            if (classTarget.packageName().isBlank()) {
                Target defaultPackage = Target.packageTarget("");
                targetsByKey.putIfAbsent(defaultPackage.key(), defaultPackage);
            } else {
                addPackageTargets(classTarget.packageName());
            }
        }
        for (BytecodeInventory.MethodInsight insight : inventory.methods()) {
            if (!eligibleMethod(insight)) continue;
            methodsByOwner.computeIfAbsent(insight.owner(), ignored -> new ArrayList<>()).add(insight);
            Target method = Target.methodTarget(insight.owner(), insight.name(), insight.descriptor());
            targetsByKey.put(method.key(), method);
        }
        methodsByOwner.values().forEach(methods -> methods.sort(Comparator
                .comparing(BytecodeInventory.MethodInsight::name)
                .thenComparing(BytecodeInventory.MethodInsight::descriptor)));
    }

    private static boolean eligibleMethod(BytecodeInventory.MethodInsight method) {
        if ("<init>".equals(method.name()) || "<clinit>".equals(method.name())) return false;
        String flags = method.flags() == null ? "" : method.flags().toLowerCase(Locale.ROOT);
        if (flags.contains("abstract") || flags.contains("native") || flags.contains("bridge")) return false;
        return !flags.contains("synthetic") || method.name().startsWith("lambda$");
    }

    private void addPackageTargets(String packageName) {
        if (packageName.isBlank()) return;
        StringBuilder current = new StringBuilder();
        for (String part : packageName.split("\\.")) {
            if (!current.isEmpty()) current.append('.');
            current.append(part);
            Target target = Target.packageTarget(current.toString());
            targetsByKey.putIfAbsent(target.key(), target);
        }
    }

    private void rebuildTree(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        TreeItem<Target> root = new TreeItem<>(Target.info("Project classes"));
        root.setExpanded(true);
        Map<String, TreeItem<Target>> packages = new LinkedHashMap<>();

        if (mode == Mode.CLASSES_AND_PACKAGES) {
            inventory.classes().stream()
                    .sorted(Comparator.comparing(BytecodeInventory.ClassInsight::name))
                    .map(insight -> Target.classTarget(insight.name()))
                    .filter(target -> query.isBlank() || target.searchableText().contains(query))
                    .forEach(target -> {
                        TreeItem<Target> parent = ensurePackage(root, packages, target.packageName(), true);
                        parent.getChildren().add(checkItem(target));
                    });
        } else {
            for (Map.Entry<String, List<BytecodeInventory.MethodInsight>> entry : methodsByOwner.entrySet()) {
                Target classTarget = Target.classTarget(entry.getKey());
                boolean classMatches = !query.isBlank() && classTarget.searchableText().contains(query);
                List<BytecodeInventory.MethodInsight> matched = query.isBlank() || classMatches
                        ? entry.getValue()
                        : entry.getValue().stream()
                        .filter(method -> Target.methodTarget(method.owner(), method.name(), method.descriptor())
                                .searchableText().contains(query))
                        .toList();
                if (!query.isBlank() && matched.isEmpty()) continue;
                TreeItem<Target> parent = ensurePackage(root, packages, classTarget.packageName(), false);
                TreeItem<Target> classNode = new TreeItem<>(classTarget);
                parent.getChildren().add(classNode);
                if (query.isBlank()) addLazyMethods(classNode, entry.getValue());
                else populateMethods(classNode, matched);
            }
        }

        if (root.getChildren().isEmpty()) {
            root.getChildren().add(new TreeItem<>(Target.info(inventory.classes().isEmpty()
                    ? "Analyze an input JAR to browse its packages, classes, and methods."
                    : "No targets match this search.")));
        }
        tree.setRoot(root);
        if (!query.isBlank()) expandAll(root);
    }

    private TreeItem<Target> ensurePackage(TreeItem<Target> root,
                                           Map<String, TreeItem<Target>> packages,
                                           String packageName, boolean selectable) {
        if (packageName.isBlank()) {
            return packages.computeIfAbsent("", ignored -> {
                TreeItem<Target> item = new TreeItem<>(Target.packageTarget(""));
                root.getChildren().add(item);
                return item;
            });
        }
        StringBuilder path = new StringBuilder();
        TreeItem<Target> parent = root;
        for (String segment : packageName.split("\\.")) {
            if (!path.isEmpty()) path.append('.');
            path.append(segment);
            String key = path.toString();
            TreeItem<Target> existing = packages.get(key);
            if (existing == null) {
                Target target = targetsByKey.getOrDefault("package:" + key, Target.packageTarget(key));
                existing = selectable ? checkItem(target) : new TreeItem<>(target);
                packages.put(key, existing);
                parent.getChildren().add(existing);
            }
            parent = existing;
        }
        return parent;
    }

    private CheckBoxTreeItem<Target> checkItem(Target target) {
        CheckBoxTreeItem<Target> item = new CheckBoxTreeItem<>(target);
        item.setIndependent(true);
        item.selectedProperty().addListener((obs, old, selected) -> {
            if (selected) selectedKeys.add(target.key());
            else selectedKeys.remove(target.key());
        });
        item.setSelected(selectedKeys.contains(target.key()));
        return item;
    }

    private void addLazyMethods(TreeItem<Target> classNode, List<BytecodeInventory.MethodInsight> methods) {
        classNode.getChildren().add(new TreeItem<>(Target.info("Expand to load " + methods.size() + " methods")));
        classNode.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && classNode.getChildren().size() == 1
                    && classNode.getChildren().getFirst().getValue().kind() == Kind.INFO) {
                populateMethods(classNode, methods);
            }
        });
    }

    private void populateMethods(TreeItem<Target> classNode, List<BytecodeInventory.MethodInsight> methods) {
        classNode.getChildren().clear();
        for (BytecodeInventory.MethodInsight method : methods) {
            classNode.getChildren().add(checkItem(
                    Target.methodTarget(method.owner(), method.name(), method.descriptor())));
        }
        classNode.setExpanded(true);
    }

    private void updateSummary() {
        int count = selectedTargets().size();
        selectionSummary.setText(count == 0 ? "No targets selected"
                : count + (count == 1 ? " target selected" : " targets selected"));
    }

    private List<Target> selectedTargets() {
        List<Target> selected = selectedKeys.stream()
                .map(targetsByKey::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Target::key))
                .toList();
        if (mode == Mode.METHODS) return selected;

        List<Target> packages = selected.stream().filter(target -> target.kind() == Kind.PACKAGE).toList();
        List<Target> minimalPackages = packages.stream()
                .filter(candidate -> packages.stream().noneMatch(parent -> !parent.equals(candidate)
                        && (candidate.packageName().equals(parent.packageName())
                        || candidate.packageName().startsWith(parent.packageName() + "."))))
                .toList();
        List<Target> result = new ArrayList<>(minimalPackages);
        selected.stream().filter(target -> target.kind() == Kind.CLASS)
                .filter(target -> minimalPackages.stream().noneMatch(parent ->
                        target.packageName().equals(parent.packageName())
                                || target.packageName().startsWith(parent.packageName() + ".")))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static void expandAll(TreeItem<Target> item) {
        item.setExpanded(true);
        item.getChildren().forEach(ArchiveTargetPicker::expandAll);
    }
}
