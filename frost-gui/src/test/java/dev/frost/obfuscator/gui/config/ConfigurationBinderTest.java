package dev.frost.obfuscator.gui.config;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.protection.ProtectionProfiles;
import dev.frost.obfuscator.gui.state.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationBinderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsExistingYamlConfigurationShape() throws Exception {
        ProjectState state = new ProjectState();
        ConfigurationBinder binder = new ConfigurationBinder(state);
        state.configuration().setInput("input.jar");
        state.configuration().setOutput("protected.jar");
        ProtectionProfiles.apply(state, "Strong");
        state.configuration().getMapping().setEnabled(true);
        state.configuration().getMapping().setOutput("maps/release.txt");
        Path file = temporaryDirectory.resolve("config.yml");

        binder.save(file);
        ObfuscationConfig loaded = ConfigLoader.load(file);

        assertEquals("input.jar", loaded.getInput());
        assertEquals("protected.jar", loaded.getOutput());
        assertEquals("maps/release.txt", loaded.getMapping().getOutput());
        assertTrue(loaded.getTransformerConfig("class-rename").isEnabled());
        assertEquals(state.configuration().getTransformerConfig("flow-obfuscation").getOptions(),
                loaded.getTransformerConfig("flow-obfuscation").getOptions());
    }
}
