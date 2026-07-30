package dev.frost.obfuscator.gui.config;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.gui.protection.ProtectionProfiles;
import dev.frost.obfuscator.gui.state.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

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
        state.configuration().getMapping().setFormat("tiny");
        state.configuration().getMapping().setEncrypted(true);
        state.configuration().getMapping().setPasswordEnvironment("FROST_RELEASE_PASSWORD");
        state.configuration().getMapping().setPassword("memory-only-secret".toCharArray());
        state.configuration().getPerformance().setParallel(true);
        state.configuration().getPerformance().setParallelism(6);
        state.configuration().getPerformance().setMinimumClasses(48);
        Path file = temporaryDirectory.resolve("config.yml");

        binder.save(file);
        ObfuscationConfig loaded = ConfigLoader.load(file);

        assertEquals("input.jar", loaded.getInput());
        assertEquals("protected.jar", loaded.getOutput());
        assertEquals("maps/release.txt", loaded.getMapping().getOutput());
        assertEquals("tiny", loaded.getMapping().getFormat());
        assertTrue(loaded.getMapping().isEncrypted());
        assertEquals("FROST_RELEASE_PASSWORD", loaded.getMapping().getPasswordEnvironment());
        assertNull(loaded.getMapping().getPassword());
        assertTrue(loaded.getPerformance().isParallel());
        assertEquals(6, loaded.getPerformance().getParallelism());
        assertEquals(48, loaded.getPerformance().getMinimumClasses());
        assertFalse(Files.readString(file).contains("memory-only-secret"));
        assertTrue(loaded.getTransformerConfig("class-rename").isEnabled());
        assertEquals(state.configuration().getTransformerConfig("flow-obfuscation").getOptions(),
                loaded.getTransformerConfig("flow-obfuscation").getOptions());
    }
}
