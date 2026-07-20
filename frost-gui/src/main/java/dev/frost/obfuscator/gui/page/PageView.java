package dev.frost.obfuscator.gui.page;

import javafx.scene.Node;

public interface PageView {
    Node root();
    default void onShown() {}
    default void onHidden() {}
}
