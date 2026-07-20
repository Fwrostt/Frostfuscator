package dev.frost.obfuscator.gui.build;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.engine.ObfuscationEngine;
import dev.frost.obfuscator.engine.ProtectionStats;
import dev.frost.obfuscator.gui.analysis.BuildAnalytics;
import dev.frost.obfuscator.gui.analysis.JarAnalyzer;
import dev.frost.obfuscator.gui.analysis.ProjectAnalysis;
import dev.frost.obfuscator.gui.config.ConfigurationBinder;
import dev.frost.obfuscator.gui.console.ConsoleModel;
import dev.frost.obfuscator.gui.console.LogEntry;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.gui.validation.ProjectValidator;
import dev.frost.obfuscator.util.Logger;
import javafx.application.Platform;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class BuildController implements AutoCloseable {
    private final ProjectState state;
    private final ConfigurationBinder binder;
    private final ConsoleModel console;
    private final ProjectValidator validator;
    private final JarAnalyzer analyzer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "frostfuscator-build-controller");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> running;

    public BuildController(ProjectState state, ConfigurationBinder binder, ConsoleModel console,
                           ProjectValidator validator, JarAnalyzer analyzer) {
        this.state = state;
        this.binder = binder;
        this.console = console;
        this.validator = validator;
        this.analyzer = analyzer;
    }

    public void validate() {
        state.problems().setAll(validator.validate(state));
        boolean errors = state.problems().stream()
                .anyMatch(problem -> problem.severity() == dev.frost.obfuscator.gui.validation.Problem.Severity.ERROR);
        state.buildStatusProperty().set(errors ? "Needs attention" : "Ready to build");
        long warnings = state.problems().stream()
                .filter(problem -> problem.severity() == dev.frost.obfuscator.gui.validation.Problem.Severity.WARNING)
                .count();
        console.append(errors ? LogEntry.Level.ERROR : warnings > 0 ? LogEntry.Level.WARNING : LogEntry.Level.SUCCESS,
                errors ? "Validation found blocking errors. Open Validation for details."
                        : warnings > 0 ? "Validation completed with " + warnings + " warning"
                                + (warnings == 1 ? "." : "s.")
                                : "Validation completed successfully. The project is ready to build.");
    }

    public synchronized void build() {
        if (running != null && !running.isDone()) return;
        state.problems().setAll(validator.validate(state));
        if (validator.hasErrors(state)) {
            state.buildStatusProperty().set("Needs attention");
            console.append(LogEntry.Level.ERROR,
                    "Build stopped because validation found errors.");
            return;
        }
        ObfuscationConfig config = binder.snapshot();
        LocalDateTime started = LocalDateTime.now();
        state.busyProperty().set(true);
        state.buildSuccessfulProperty().set(false);
        state.buildProgressProperty().set(0.04);
        state.buildStatusProperty().set("Validating configuration");
        console.clear();
        console.append(LogEntry.Level.INFO, "Build started.");
        console.append(LogEntry.Level.INFO, "Input: " + config.getInput());
        console.append(LogEntry.Level.INFO, "Output: " + config.getOutput());
        console.append(LogEntry.Level.INFO, "Validating configuration and preparing the class graph.");

        Consumer<String> listener = line -> {
            console.append(line);
            updateProgress(line);
        };
        Logger.addListener(listener);
        running = executor.submit(() -> {
            try {
                ConfigLoader.validate(config);
                Platform.runLater(() -> state.buildStatusProperty().set("Protecting classes"));
                ProjectAnalysis inputAnalysis = state.analysis().analyzed()
                        ? state.analysis() : analyzer.analyze(java.nio.file.Path.of(config.getInput()));
                ProtectionStats stats = new ObfuscationEngine(config, null).run();
                Duration duration = Duration.between(started, LocalDateTime.now());
                BuildAnalytics measuredAnalytics;
                try {
                    ProjectAnalysis outputAnalysis = analyzer.analyze(java.nio.file.Path.of(config.getOutput()));
                    measuredAnalytics = BuildAnalytics.compare(inputAnalysis, outputAnalysis,
                            duration, stats.counters(), config);
                } catch (Exception analyticsFailure) {
                    measuredAnalytics = BuildAnalytics.empty();
                    console.append(LogEntry.Level.WARNING,
                            "Build completed, but post-build analytics could not inspect the output: "
                                    + analyticsFailure.getMessage());
                }
                BuildAnalytics analytics = measuredAnalytics;
                Platform.runLater(() -> {
                    state.busyProperty().set(false);
                    state.buildSuccessfulProperty().set(true);
                    state.buildProgressProperty().set(1);
                    state.buildStatusProperty().set("Build completed");
                    state.setBuildAnalytics(analytics);
                    state.buildHistory().add(0, new BuildRecord(LocalDateTime.now(), BuildRecord.Status.SUCCESS,
                            state.outputPath(), duration, "Protected JAR created"));
                });
                console.append(LogEntry.Level.SUCCESS,
                        "Build completed in " + duration.toSeconds() + " seconds. Output: " + config.getOutput());
                if (analytics.available()) {
                    console.append(LogEntry.Level.SUCCESS,
                            String.format(java.util.Locale.ROOT,
                                    "Analytics: %.1f%% strings protected, %.1f%% methods transformed, output size %+.1f%%.",
                                    analytics.stringProtectionPercent(), analytics.methodProtectionPercent(),
                                    analytics.sizeGrowthPercent()));
                }
            } catch (CancellationException exception) {
                finishCancelled(started);
            } catch (Throwable throwable) {
                if (Thread.currentThread().isInterrupted()) {
                    finishCancelled(started);
                } else {
                    Duration duration = Duration.between(started, LocalDateTime.now());
                    Platform.runLater(() -> {
                        state.busyProperty().set(false);
                        state.buildProgressProperty().set(0);
                        state.buildStatusProperty().set("Build failed");
                        state.buildHistory().add(0, new BuildRecord(LocalDateTime.now(), BuildRecord.Status.FAILED,
                                state.outputPath(), duration, throwable.getMessage()));
                    });
                    console.append(LogEntry.Level.ERROR,
                            "Build failed: " + (throwable.getMessage() == null ? throwable : throwable.getMessage()));
                }
            } finally {
                Logger.removeListener(listener);
            }
        });
    }

    private void finishCancelled(LocalDateTime started) {
        Duration duration = Duration.between(started, LocalDateTime.now());
        Platform.runLater(() -> {
            state.busyProperty().set(false);
            state.buildProgressProperty().set(0);
            state.buildStatusProperty().set("Build cancelled");
            state.buildHistory().add(0, new BuildRecord(LocalDateTime.now(), BuildRecord.Status.CANCELLED,
                    state.outputPath(), duration, "Cancelled by user"));
        });
        console.append(LogEntry.Level.WARNING, "Build cancellation requested.");
    }

    private void updateProgress(String line) {
        String text = line == null ? "" : line;
        double progress = text.contains("Pass 0:") ? 0.12
                : text.contains("Class hierarchy built") ? 0.22
                : text.contains("Pass 1:") ? 0.32
                : text.contains("Pass 2:") ? 0.46
                : text.contains("Pass 3:") ? 0.60
                : text.contains("Pass 4:") ? 0.72
                : text.contains("Pass 5:") ? 0.82
                : text.contains("Pass 6:") ? 0.90
                : text.contains("Protection run completed") ? 0.97
                : -1;
        String status = null;
        if (text.contains("Running transformer:")) {
            status = "Running " + text.substring(text.indexOf("Running transformer:")
                    + "Running transformer:".length()).trim();
        } else if (text.contains("Class hierarchy built")) {
            status = "Analyzing class hierarchy";
        } else if (text.contains("Applying remapping")) {
            status = "Applying mappings";
        } else if (text.contains("FrostJNI native protection")) {
            status = "Building native protection";
        } else if (text.contains("ClassLoader Encryption")) {
            status = "Encrypting protected classes";
        }
        String nextStatus = status;
        Platform.runLater(() -> {
            if (progress >= 0) state.buildProgressProperty().set(progress);
            if (nextStatus != null && !nextStatus.isBlank()) state.buildStatusProperty().set(nextStatus);
        });
    }

    public synchronized void cancel() {
        if (running != null && !running.isDone()) {
            state.buildStatusProperty().set("Cancelling");
            running.cancel(true);
        }
    }

    @Override
    public synchronized void close() {
        cancel();
        executor.shutdownNow();
    }
}
