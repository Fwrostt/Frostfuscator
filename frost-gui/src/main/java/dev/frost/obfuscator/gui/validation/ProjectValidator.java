package dev.frost.obfuscator.gui.validation;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.analysis.Recommendation;
import dev.frost.obfuscator.gui.analysis.RecommendationEngine;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.transformer.TransformerConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProjectValidator {
    private final RecommendationEngine recommendationEngine;

    public ProjectValidator(RecommendationEngine recommendationEngine) {
        this.recommendationEngine = recommendationEngine;
    }

    public List<Problem> validate(ProjectState state) {
        List<Problem> problems = new ArrayList<>();
        ObfuscationConfig config = state.configuration();
        try {
            ConfigLoader.validate(config);
        } catch (Exception exception) {
            problems.add(new Problem(Problem.Severity.ERROR, "config",
                    "Configuration needs attention", message(exception),
                    inputMissing(config) ? "Choose input JAR" : "", null));
        }

        if (state.analysis().reflectionUsage() && renamingEnabled(config)) {
            problems.add(new Problem(Problem.Severity.WARNING, "reflection",
                    "Reflection-sensitive classes may be renamed",
                    "Runtime name lookup was detected. Apply the suggested exclusions before using aggressive renaming.",
                    "Add keep rules", current -> {
                        List<String> rules = new ArrayList<>(current.configuration().getExclusions());
                        for (String rule : current.analysis().exclusions()) if (!rules.contains(rule)) rules.add(rule);
                        current.configuration().setExclusions(rules);
                        current.touch();
                    }));
        }
        if (state.analysis().serviceLoaders() && renamingEnabled(config)) {
            problems.add(new Problem(Problem.Severity.WARNING, "services",
                    "Service provider names require preservation",
                    "ServiceLoader entrypoints are discovered by class name and can break after renaming.",
                    "Keep providers", current -> {
                        List<String> includes = new ArrayList<>(current.configuration().getInclusions());
                        for (String rule : current.analysis().keepRules()) if (!includes.contains(rule)) includes.add(rule);
                        current.configuration().setInclusions(includes);
                        current.touch();
                    }));
        }
        if (state.analysis().signed()) {
            problems.add(new Problem(Problem.Severity.WARNING, "signed",
                    "The input signature will become invalid",
                    "Modified JAR entries cannot retain the original signature. Remove signature metadata and re-sign the final artifact.",
                    "", null));
        }
        if (state.analysis().nativeLibraries() && enabled(config, "classloader-encryption")) {
            problems.add(new Problem(Problem.Severity.WARNING, "native-loader",
                    "Encrypted loading may affect native extraction",
                    "Embedded native libraries often depend on stable resource paths and extraction behavior.",
                    "Disable encrypted loader", current -> {
                        setEnabled(current.configuration(), "classloader-encryption", false);
                        current.touch();
                    }));
        }
        if (enabled(config, "classloader-encryption") && enabled(config, "integrity")) {
            problems.add(new Problem(Problem.Severity.ERROR, "loader-integrity",
                    "Encrypted ClassLoader conflicts with Integrity Index",
                    "Both protections take ownership of emitted class resources and cannot run together.",
                    "Disable Integrity Index", current -> {
                        setEnabled(current.configuration(), "integrity", false);
                        current.touch();
                    }));
        }
        if (config.getFrostJNI() != null && config.getFrostJNI().isEnabled()
                && enabled(config, "classloader-encryption")) {
            problems.add(new Problem(Problem.Severity.ERROR, "jni-loader",
                    "FrostJNI conflicts with Encrypted ClassLoader",
                    "Native conversion and encrypted class loading require incompatible runtime bootstraps.",
                    "Disable Encrypted ClassLoader", current -> {
                        setEnabled(current.configuration(), "classloader-encryption", false);
                        current.touch();
                    }));
        }

        List<Recommendation> recommendations = recommendationEngine.recommend(state.analysis(), config,
                state.profileProperty().get(), state.outputSizeLimitMbProperty().get(),
                state.runtimeOverheadPreferenceProperty().get());
        for (Recommendation recommendation : recommendations) {
            if (problems.stream().noneMatch(problem -> problem.id().equals(recommendation.id()))) {
                problems.add(new Problem(Problem.Severity.RECOMMENDATION, recommendation.id(),
                        recommendation.title(), recommendation.explanation(), recommendation.quickFix(), null));
            }
        }
        return problems;
    }

    public boolean hasErrors(ProjectState state) {
        return validate(state).stream().anyMatch(problem -> problem.severity() == Problem.Severity.ERROR);
    }

    private static boolean inputMissing(ObfuscationConfig config) {
        try {
            return config.getInput() == null || config.getInput().isBlank() || !java.nio.file.Files.exists(Path.of(config.getInput()));
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean renamingEnabled(ObfuscationConfig config) {
        return enabled(config, "class-rename") || enabled(config, "field-rename") || enabled(config, "method-rename");
    }

    private static boolean enabled(ObfuscationConfig config, String name) {
        TransformerConfig value = config.getTransformerConfig(name);
        return value != null && value.isEnabled();
    }

    private static void setEnabled(ObfuscationConfig config, String name, boolean enabled) {
        config.getTransformers().computeIfAbsent(name, key -> new TransformerConfig()).setEnabled(enabled);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
