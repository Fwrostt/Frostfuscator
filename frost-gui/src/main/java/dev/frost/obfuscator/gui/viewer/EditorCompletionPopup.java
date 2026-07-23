package dev.frost.obfuscator.gui.viewer;

import dev.frost.obfuscator.gui.component.Ui;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Floating JavaFX Popup window attached to the editor caret for IDE autocompletion suggestions.
 * Styled cleanly via frost-gui.css design system.
 */
public final class EditorCompletionPopup {

    private final Popup popup = new Popup();
    private final ListView<IdeCompletionService.CompletionCandidate> listView = new ListView<>();
    private final VBox container = new VBox(listView);
    private CodeArea targetCodeArea;
    private int replaceStartPos;
    private Consumer<IdeCompletionService.CompletionCandidate> onInsertCallback;

    public EditorCompletionPopup() {
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        container.getStyleClass().add("editor-completion-popup");

        listView.getStyleClass().addAll("viewer-list", "completion-list-view");
        listView.setPrefWidth(360);
        listView.setPrefHeight(200);
        listView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(IdeCompletionService.CompletionCandidate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(null);
                    Label badge = new Label(switch (item.kind()) {
                        case CLASS -> "C";
                        case METHOD -> "M";
                        case FIELD -> "F";
                        case PACKAGE -> "P";
                    });
                    badge.getStyleClass().addAll("completion-type-badge", switch (item.kind()) {
                        case CLASS -> "completion-badge-class";
                        case METHOD -> "completion-badge-method";
                        case FIELD -> "completion-badge-field";
                        case PACKAGE -> "completion-badge-package";
                    });

                    Label displayLbl = new Label(item.displayText());
                    displayLbl.getStyleClass().add("completion-display-name");

                    Label detailLbl = new Label(item.detail());
                    detailLbl.getStyleClass().add("completion-detail-text");

                    HBox box = new HBox(8, badge, displayLbl, Ui.spacer(), detailLbl);
                    box.setAlignment(Pos.CENTER_LEFT);
                    HBox.setHgrow(displayLbl, Priority.NEVER);
                    box.setPadding(new Insets(2, 4, 2, 4));
                    setGraphic(box);
                }
            }
        });

        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                insertSelected();
            }
        });

        popup.getContent().add(container);
    }

    public void showSuggestions(
            CodeArea codeArea,
            int replaceStartPos,
            List<IdeCompletionService.CompletionCandidate> candidates,
            Consumer<IdeCompletionService.CompletionCandidate> onInsert) {

        if (candidates == null || candidates.isEmpty()) {
            hide();
            return;
        }

        this.targetCodeArea = codeArea;
        this.replaceStartPos = replaceStartPos;
        this.onInsertCallback = onInsert;

        listView.setItems(FXCollections.observableArrayList(candidates));
        listView.getSelectionModel().select(0);

        Optional<Bounds> boundsOpt = codeArea.getCaretBounds();
        if (boundsOpt.isPresent() && codeArea.getScene() != null && codeArea.getScene().getWindow() != null) {
            Bounds bounds = boundsOpt.get();
            double x = bounds.getMinX();
            double y = bounds.getMaxY() + 4;
            if (!popup.isShowing()) {
                popup.show(codeArea.getScene().getWindow(), x, y);
            } else {
                popup.setX(x);
                popup.setY(y);
            }
        }
    }

    public boolean handleKeyPress(KeyEvent event) {
        if (!popup.isShowing()) return false;

        if (event.getCode() == KeyCode.DOWN) {
            int current = listView.getSelectionModel().getSelectedIndex();
            if (current < listView.getItems().size() - 1) {
                listView.getSelectionModel().select(current + 1);
                listView.scrollTo(current + 1);
            }
            return true;
        } else if (event.getCode() == KeyCode.UP) {
            int current = listView.getSelectionModel().getSelectedIndex();
            if (current > 0) {
                listView.getSelectionModel().select(current - 1);
                listView.scrollTo(current - 1);
            }
            return true;
        } else if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.TAB) {
            insertSelected();
            return true;
        } else if (event.getCode() == KeyCode.ESCAPE) {
            hide();
            return true;
        }
        return false;
    }

    public void insertSelected() {
        IdeCompletionService.CompletionCandidate selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null && targetCodeArea != null) {
            int caretPos = targetCodeArea.getCaretPosition();
            if (replaceStartPos <= caretPos && replaceStartPos >= 0) {
                targetCodeArea.replaceText(replaceStartPos, caretPos, selected.insertText());
            }
            if (onInsertCallback != null) {
                onInsertCallback.accept(selected);
            }
        }
        hide();
    }

    public void hide() {
        if (popup.isShowing()) {
            popup.hide();
        }
    }

    public boolean isShowing() {
        return popup.isShowing();
    }
}
