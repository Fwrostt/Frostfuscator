package dev.frost.obfuscator.gui.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleModelTest {

    @Test
    void shortAndPunctuatedMessagesCannotOverflowReferenceDetection() {
        assertDoesNotThrow(() -> ConsoleModel.findReference("Build."));
        assertDoesNotThrow(() -> ConsoleModel.findReference("INFO."));
        assertDoesNotThrow(() -> ConsoleModel.findReference("Validating configuration and preparing."));
        assertEquals("", ConsoleModel.findReference("Build."));
    }

    @Test
    void classAndMethodReferencesRemainDetectable() {
        assertEquals("com.example.security.LicenseManager#verify:42",
                ConsoleModel.findReference(
                        "Failed in com.example.security.LicenseManager#verify:42 while protecting the class."));
        assertEquals("com.example.PluginMain",
                ConsoleModel.findReference("Entrypoint com.example.PluginMain is reflection-sensitive."));
    }

    @Test
    void transportLevelIsNotDuplicatedAndTransformerTagIsPreserved() {
        LogEntry entry = ConsoleModel.parse(
                "[INFO] [field-rename] Renamed field com/example/Fixture.value -> a");
        assertEquals(LogEntry.Level.INFO, entry.level());
        assertEquals("field-rename", entry.transformer());
        assertEquals("[field-rename] Renamed field com/example/Fixture.value -> a", entry.message());
    }
}
