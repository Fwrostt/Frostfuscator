package dev.frost.obfuscator.gui.state;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.analysis.BuildAnalytics;
import dev.frost.obfuscator.gui.build.BuildRecord;
import dev.frost.obfuscator.gui.validation.Problem;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;

public final class ProjectState {
    private final ObjectProperty<ObfuscationConfig> configuration =
            new SimpleObjectProperty<>(this, "configuration", new ObfuscationConfig());
    private final ObjectProperty<ProjectAnalysis> analysis =
            new SimpleObjectProperty<>(this, "analysis", ProjectAnalysis.empty());
    private final ObjectProperty<BuildAnalytics> buildAnalytics =
            new SimpleObjectProperty<>(this, "buildAnalytics", BuildAnalytics.empty());
    private final StringProperty profile = new SimpleStringProperty(this, "profile", "Development");
    private final StringProperty goal = new SimpleStringProperty(this, "goal", "Best compatibility");
    private final DoubleProperty outputSizeLimitMb = new SimpleDoubleProperty(this, "outputSizeLimitMb", 0);
    private final DoubleProperty runtimeOverheadPreference =
            new SimpleDoubleProperty(this, "runtimeOverheadPreference", 0.35);
    private final BooleanProperty dirty = new SimpleBooleanProperty(this, "dirty", false);
    private final BooleanProperty busy = new SimpleBooleanProperty(this, "busy", false);
    private final BooleanProperty buildSuccessful = new SimpleBooleanProperty(this, "buildSuccessful", false);
    private final StringProperty buildStatus = new SimpleStringProperty(this, "buildStatus", "Ready");
    private final DoubleProperty buildProgress = new SimpleDoubleProperty(this, "buildProgress", 0);
    private final LongProperty revision = new SimpleLongProperty(this, "revision", 0);
    private final ObservableList<Problem> problems = FXCollections.observableArrayList();
    private final ObservableList<BuildRecord> buildHistory = FXCollections.observableArrayList();

    public ObjectProperty<ObfuscationConfig> configurationProperty() { return configuration; }
    public ObfuscationConfig configuration() { return configuration.get(); }
    public void replaceConfiguration(ObfuscationConfig value) {
        configuration.set(value);
        dirty.set(false);
        buildSuccessful.set(false);
        revision.set(revision.get() + 1);
    }

    public ObjectProperty<ProjectAnalysis> analysisProperty() { return analysis; }
    public ProjectAnalysis analysis() { return analysis.get(); }
    public void setAnalysis(ProjectAnalysis value) {
        analysis.set(value == null ? ProjectAnalysis.empty() : value);
        buildAnalytics.set(BuildAnalytics.empty());
        revision.set(revision.get() + 1);
    }
    public ObjectProperty<BuildAnalytics> buildAnalyticsProperty() { return buildAnalytics; }
    public BuildAnalytics buildAnalytics() { return buildAnalytics.get(); }
    public void setBuildAnalytics(BuildAnalytics value) {
        buildAnalytics.set(value == null ? BuildAnalytics.empty() : value);
    }

    public StringProperty profileProperty() { return profile; }
    public StringProperty goalProperty() { return goal; }
    public DoubleProperty outputSizeLimitMbProperty() { return outputSizeLimitMb; }
    public DoubleProperty runtimeOverheadPreferenceProperty() { return runtimeOverheadPreference; }
    public BooleanProperty dirtyProperty() { return dirty; }
    public BooleanProperty busyProperty() { return busy; }
    public BooleanProperty buildSuccessfulProperty() { return buildSuccessful; }
    public StringProperty buildStatusProperty() { return buildStatus; }
    public DoubleProperty buildProgressProperty() { return buildProgress; }
    public LongProperty revisionProperty() { return revision; }
    public ObservableList<Problem> problems() { return problems; }
    public ObservableList<BuildRecord> buildHistory() { return buildHistory; }

    public void touch() {
        dirty.set(true);
        buildSuccessful.set(false);
        revision.set(revision.get() + 1);
    }

    public Path outputPath() {
        String output = configuration().getOutput();
        return output == null || output.isBlank() ? null : Path.of(output);
    }
}
