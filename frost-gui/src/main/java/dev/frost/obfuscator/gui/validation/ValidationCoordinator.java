package dev.frost.obfuscator.gui.validation;

import dev.frost.obfuscator.gui.state.ProjectState;
import javafx.application.Platform;
import org.reactfx.EventSource;
import org.reactfx.Subscription;

import java.time.Duration;

public final class ValidationCoordinator implements AutoCloseable {
    private final ProjectState state;
    private final ProjectValidator validator;
    private final EventSource<Number> revisions = new EventSource<>();
    private final Subscription subscription;

    public ValidationCoordinator(ProjectState state, ProjectValidator validator) {
        this.state = state;
        this.validator = validator;
        subscription = revisions.successionEnds(Duration.ofMillis(360))
                .subscribe(value -> Platform.runLater(this::validateNow));
        state.revisionProperty().addListener((obs, old, value) -> revisions.push(value));
        Platform.runLater(this::validateNow);
    }

    public void requestValidation() {
        revisions.push(state.revisionProperty().get());
    }

    public void validateNow() {
        state.problems().setAll(validator.validate(state));
    }

    @Override
    public void close() {
        subscription.unsubscribe();
    }
}
