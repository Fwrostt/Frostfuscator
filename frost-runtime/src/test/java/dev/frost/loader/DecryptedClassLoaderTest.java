package dev.frost.loader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DecryptedClassLoaderTest {
    @Test
    void selectsHighestSupportedMultiReleaseClassEntry() {
        List<String> entries = List.of(
                "sample.Feature",
                "META-INF/versions/11/sample/Feature.class",
                "META-INF/versions/17/sample/Feature.class",
                "META-INF/versions/22/sample/Feature.class",
                "sample/Helper.class"
        );

        Map<String, String> selected = DecryptedClassLoader.selectRegistryEntries(entries, 17);

        assertEquals("META-INF/versions/17/sample/Feature.class", selected.get("sample.Feature"));
        assertEquals("sample/Helper.class", selected.get("sample.Helper"));
        assertFalse(selected.containsValue("META-INF/versions/22/sample/Feature.class"));
    }

    @Test
    void fallsBackToBaseEntryWhenRuntimePredatesVersionedVariants() {
        Map<String, String> selected = DecryptedClassLoader.selectRegistryEntries(List.of(
                "sample/Feature.class",
                "META-INF/versions/17/sample/Feature.class"), 11);

        assertEquals("sample/Feature.class", selected.get("sample.Feature"));
    }
}
