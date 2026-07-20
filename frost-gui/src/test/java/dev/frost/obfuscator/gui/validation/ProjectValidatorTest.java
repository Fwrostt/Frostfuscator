package dev.frost.obfuscator.gui.validation;

import dev.frost.obfuscator.gui.analysis.RecommendationEngine;
import dev.frost.obfuscator.gui.config.ConfigurationBinder;
import dev.frost.obfuscator.gui.state.ProjectState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectValidatorTest {
    @Test
    void reportsKnownEncryptedLoaderConflict() {
        ProjectState state = new ProjectState();
        new ConfigurationBinder(state);
        state.configuration().getTransformerConfig("classloader-encryption").setEnabled(true);
        state.configuration().getTransformerConfig("integrity").setEnabled(true);

        var problems = new ProjectValidator(new RecommendationEngine()).validate(state);

        assertTrue(problems.stream().anyMatch(problem ->
                problem.id().equals("loader-integrity") && problem.severity() == Problem.Severity.ERROR));
    }
}
