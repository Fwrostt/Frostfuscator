package dev.frost.obfuscator.gui.page;

import dev.frost.graph.EdgeType;
import dev.frost.graph.Graph;
import dev.frost.graph.GraphEdge;
import dev.frost.graph.GraphMetadata;
import dev.frost.graph.GraphNode;
import dev.frost.graph.GraphType;
import dev.frost.graph.NodeType;
import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.app.AppShell;
import dev.frost.obfuscator.gui.component.CustomComboBox;
import dev.frost.obfuscator.gui.component.SearchableDropdown;
import dev.frost.obfuscator.gui.graph.GraphViewer;
import dev.frost.obfuscator.gui.navigation.PageId;
import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphPageVisualTest extends ApplicationTest {
    private AppContext context;
    private AppShell shell;
    private Stage stage;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        Path testDirectory = Files.createTempDirectory("frost-graph-page-test");
        Path input = testDirectory.resolve("graph-fixture.jar");
        writeFixtureJar(input);
        PreferencesStore preferences = new PreferencesStore(testDirectory);
        context = AppContext.create(stage, preferences);
        context.projectState().configuration().setInput(input.toString());
        shell = new AppShell(context);
        Scene scene = new Scene(shell.root(), 1600, 960);
        scene.getStylesheets().add(getClass().getResource("/frost-gui.css").toExternalForm());
        stage.setScene(scene);
        context.themeManager().attach(scene, shell.root());
        shell.navigate(PageId.GRAPHS);
        stage.setOpacity(0);
        stage.show();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            interact(() -> shell.navigate(PageId.OVERVIEW));
            context.close();
        }
    }

    @Test
    void graphWorkspaceUsesFocusedControlsScalableCanvasAndLiveThemeTokens() throws Exception {
        waitForIndex();
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup(".graph-target-controls").query().isManaged());
        assertTrue(lookup("Limits").query().isManaged());
        assertTrue(lookup(".graph-inspector").queryAll().isEmpty(), "details stay out of the way initially");

        Region viewerRegion = lookup(".graph-viewer").query();
        assertEquals(0, viewerRegion.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius(), 0.01);
        assertEquals(0, viewerRegion.getBorder().getStrokes().getFirst().getRadii().getTopLeftHorizontalRadius(), 0.01);
        snapshot("graph-page-empty-oled.png");

        GraphViewer viewer = lookup(".graph-viewer").query();
        interact(() -> viewer.setGraph(sampleGraph()));
        WebView web = lookup(".graph-web-view").query();
        waitForLoad(web);
        WaitForAsyncUtils.waitForFxEvents();
        snapshot("graph-page-loaded-oled.png");

        interact(() -> web.getEngine().executeScript(
                "window.frostSelected='app/Main'"));
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, () -> !lookup(".graph-inspector").queryAll().isEmpty());
        snapshot("graph-page-details-oled.png");

        interact(() -> context.themeManager().select("frost"));
        waitForLoad(web);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(shell.root().getStyle().contains("-fx-bg:#F3F8FB"));
        snapshot("graph-page-details-frost.png");

        interact(() -> {
            context.themeManager().select("oled-black");
            stage.setWidth(900);
            stage.setHeight(760);
        });
        waitForLoad(web);
        interact(shell.root()::layout);
        WaitForAsyncUtils.waitForFxEvents();
        Pane commandBar = lookup(".graph-command-bar").query();
        Pane primaryRow = lookup(".graph-primary-row").query();
        assertTrue(lookup(".graph-refresh-button").queryAll().stream().noneMatch(Node::isManaged),
                "the redundant refresh action yields space at the minimum window width");
        assertTrue(lookup("Details").queryAll().stream().anyMatch(Node::isManaged));
        assertTrue(lookup("Export").queryAll().stream().anyMatch(Node::isManaged));
        assertChildrenFit(commandBar, "graph command bar");
        assertChildrenFit(primaryRow, "graph setup row");
        snapshot("graph-page-compact-oled.png");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void methodPickerStaysBoundedAndAnalyzeRunsAfterSelection() throws Exception {
        waitForIndex();
        CustomComboBox analysis = lookup(".graph-analysis-selector").query();
        SearchableDropdown<dev.frost.graph.bytecode.BytecodeClassInfo> classes =
                lookup(".graph-class-selector").query();
        SearchableDropdown<dev.frost.graph.bytecode.BytecodeMethodInfo> methods =
                lookup(".graph-method-selector").query();
        javafx.scene.control.Button analyze = lookup(".graph-analyze-button").query();
        Label status = lookup(".graph-status-copy").query();
        Object classCalls = analysis.getValues().stream()
                .filter(value -> String.valueOf(value).contains("id=calls"))
                .findFirst().orElseThrow();
        interact(() -> analysis.setValue(classCalls));
        assertFalse(methods.getParent().isManaged(), "class call flow must not ask for one method");

        Node arrow = analysis.lookup(".arrow");
        interact(analysis::show);
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, () -> analysis.isShowing() && arrow.getRotate() == 180);
        interact(analysis::hide);

        CustomComboBox<?> flow = lookup(".graph-flow-direction").query();
        javafx.scene.control.MenuButton limits = lookup(".graph-limits-button").query();
        assertEquals(flow.localToScene(flow.getBoundsInLocal()).getMaxY(),
                limits.localToScene(limits.getBoundsInLocal()).getMaxY(), 1,
                "Limits and Flow controls should share a baseline");
        interact(limits::show);
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, limits::isShowing);
        Region limitsItem = lookup(".graph-limits-menu-item").query();
        Region limitsPanel = lookup(".graph-limits-panel").query();
        assertTrue(limitsItem.getBackground() == null || limitsItem.getBackground().getFills().stream()
                        .noneMatch(fill -> fill.getFill().isOpaque()),
                "the limits content must not inherit the focused menu-item rim");
        assertTrue(limitsPanel.getBackground() == null || limitsPanel.getBackground().getFills().stream()
                        .noneMatch(fill -> fill.getFill().isOpaque()),
                "the limits content should use the popup surface instead of a nested panel border");
        interact(limits::hide);

        interact(() -> {
            classes.setValue(classes.getValues().stream()
                    .filter(item -> item.internalName().equals("fixture/Flow")).findFirst().orElseThrow());
            analyze.fire();
        });
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () -> status.getText().contains("nodes"));
        WebView web = lookup(".graph-web-view").query();
        waitForLoad(web);
        AtomicReference<String> rendererError = new AtomicReference<>();
        interact(() -> rendererError.set(String.valueOf(web.getEngine().executeScript("window.frostError||''"))));
        assertEquals("", rendererError.get());
        snapshot("graph-page-class-call-flow-oled.png");

        Object controlFlow = analysis.getValues().stream()
                .filter(value -> String.valueOf(value).contains("id=cfg"))
                .findFirst().orElseThrow();
        interact(() -> analysis.setValue(controlFlow));

        interact(() -> classes.setValue(classes.getValues().getFirst()));
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, () -> !methods.getValues().isEmpty());

        assertFalse(analyze.isDisabled(), "Analyze stays actionable and explains missing scope");
        interact(analyze::fire);
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, methods::isShowing);
        VBox popup = lookup(".searchable-dropdown-popup").query();
        assertTrue(popup.getWidth() <= 521, "method popup must stay bounded to a useful desktop width");
        assertTrue(popup.getWidth() >= methods.getWidth() - 1, "popup should align with its control");

        interact(() -> {
            methods.setValue(methods.getValues().stream().filter(method -> method.name().equals("run"))
                    .findFirst().orElseThrow());
            methods.hide();
            analyze.fire();
        });
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS,
                () -> status.getText().contains("nodes") && !status.getText().equals("No graph loaded"));
        snapshot("graph-page-control-flow-oled.png");
    }

    private void waitForIndex() throws Exception {
        SearchableDropdown<?> classes = lookup(".graph-class-selector").query();
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () -> !classes.getValues().isEmpty());
    }

    private void snapshot(String name) throws Exception {
        String output = System.getenv("FROST_VISUAL_OUTPUT");
        if (output == null || output.isBlank()) return;
        Path directory = Path.of(output);
        Files.createDirectories(directory);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        interact(() -> image.set(shell.root().snapshot(new SnapshotParameters(), null)));
        ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", directory.resolve(name).toFile());
    }

    private void waitForLoad(WebView web) throws Exception {
        AtomicBoolean loaded = new AtomicBoolean();
        interact(() -> {
            loaded.set(web.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED);
            web.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) loaded.set(true);
            });
        });
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, loaded::get);
    }

    private static void assertChildrenFit(Pane pane, String description) {
        double furthestEdge = pane.getChildrenUnmodifiable().stream()
                .filter(Node::isManaged)
                .mapToDouble(node -> node.getLayoutX() + node.getLayoutBounds().getMaxX())
                .max().orElse(0);
        assertTrue(furthestEdge <= pane.getWidth() + 1,
                description + " overflows at the minimum window width: " + furthestEdge + " > " + pane.getWidth());
    }

    private static void writeFixtureJar(Path path) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Flow", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(I)V", null, null);
        org.objectweb.asm.Label exit = new org.objectweb.asm.Label();
        run.visitCode();
        run.visitVarInsn(Opcodes.ILOAD, 0);
        run.visitJumpInsn(Opcodes.IFEQ, exit);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Helper", "out", "()V", false);
        run.visitInsn(Opcodes.NOP);
        run.visitLabel(exit);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();

        ClassWriter helper = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        helper.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Helper", null, "java/lang/Object", null);
        MethodVisitor out = helper.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "out", "()V", null, null);
        out.visitCode();
        out.visitInsn(Opcodes.RETURN);
        out.visitMaxs(0, 0);
        out.visitEnd();
        MethodVisitor entry = helper.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "entry", "()V", null, null);
        entry.visitCode();
        entry.visitInsn(Opcodes.ICONST_0);
        entry.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Flow", "run", "(I)V", false);
        entry.visitInsn(Opcodes.RETURN);
        entry.visitMaxs(0, 0);
        entry.visitEnd();
        helper.visitEnd();

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("fixture/Flow.class"));
            jar.write(writer.toByteArray());
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("fixture/Helper.class"));
            jar.write(helper.toByteArray());
            jar.closeEntry();
        }
    }

    private static Graph sampleGraph() {
        List<GraphNode> nodes = List.of(
                new GraphNode("app/Main", "Main", NodeType.CLASS,
                        GraphMetadata.builder().put("package", "app").put("methods", 8).build()),
                new GraphNode("app/Service", "Service", NodeType.CLASS,
                        GraphMetadata.builder().put("package", "app").put("methods", 14).build()),
                new GraphNode("app/Repository", "Repository", NodeType.CLASS,
                        GraphMetadata.builder().put("package", "app").put("methods", 6).build()),
                new GraphNode("java/util/List", "List", NodeType.LIBRARY_CLASS,
                        GraphMetadata.builder().put("library", true).build()));
        List<GraphEdge> edges = List.of(
                new GraphEdge(null, "app/Main", "app/Service", EdgeType.DEPENDS_ON, "uses", GraphMetadata.EMPTY),
                new GraphEdge(null, "app/Service", "app/Repository", EdgeType.DEPENDS_ON, "reads", GraphMetadata.EMPTY),
                new GraphEdge(null, "app/Repository", "java/util/List", EdgeType.DEPENDS_ON, "returns", GraphMetadata.EMPTY));
        return new Graph("sample", "Sample dependencies", GraphType.CLASS_DEPENDENCY,
                nodes, edges, GraphMetadata.EMPTY, List.of(), false);
    }
}
