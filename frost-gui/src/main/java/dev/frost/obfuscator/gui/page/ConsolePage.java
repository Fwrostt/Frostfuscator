package dev.frost.obfuscator.gui.page;

import dev.frost.obfuscator.gui.app.AppContext;
import dev.frost.obfuscator.gui.component.Ui;
import dev.frost.obfuscator.gui.console.ConsoleView;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;

public final class ConsolePage implements PageView {
    private final BorderPane root = new BorderPane();
    private final ConsoleView console;

    public ConsolePage(AppContext context) {
        console = new ConsoleView(context);
        root.getStyleClass().addAll("page", "console-page");
        root.setPadding(Ui.pageInsets());
        root.setTop(Ui.pageHeader("Console", "Search, filter, copy, and export timestamped build output."));
        BorderPane.setMargin(root.getTop(), new javafx.geometry.Insets(0, 0, Ui.SPACE_6, 0));
        root.setCenter(console.root());
    }

    @Override
    public Node root() { return root; }

    @Override
    public void onShown() {
        console.setActive(true);
    }

    @Override
    public void onHidden() {
        console.setActive(false);
    }
}
