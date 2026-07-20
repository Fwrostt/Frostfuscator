package dev.frost.obfuscator.gui.protection;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TransformerCatalogTest {

    private final TransformerCatalog catalog = new TransformerCatalog();

    @Test
    void nativeProtectionContainsOnlyFrostJni() {
        var nativePasses = catalog.category(TransformerCatalog.Category.NATIVE);

        assertEquals(1, nativePasses.size());
        assertEquals("frostjni", nativePasses.getFirst().name());
        assertEquals("FrostJNI", nativePasses.getFirst().title());
        assertTrue(nativePasses.getFirst().description().contains("native code"));
    }

    @Test
    void runtimeDefensesAreNotMisclassifiedAsNativeProtection() {
        Set<String> runtimeGuards = names(TransformerCatalog.Category.RUNTIME_GUARDS);

        assertTrue(runtimeGuards.contains("anti-debug"));
        assertTrue(runtimeGuards.contains("integrity"));
        assertTrue(runtimeGuards.contains("runtime-self-checksum"));
        assertTrue(runtimeGuards.contains("license-guard"));
        assertFalse(runtimeGuards.contains("frostjni"));
    }

    @Test
    void funsiesCategoryUsesTheEngineTaxonomy() {
        Set<String> funsies = names(TransformerCatalog.Category.FUNSIES);

        assertTrue(funsies.contains("inject-banner"));
        assertTrue(funsies.contains("emoji-hell"));
        assertTrue(funsies.contains("copypasta-injector"));
        assertTrue(funsies.contains("chinese-mode"));
        assertTrue(funsies.contains("decompiler-zip-ties"));
        assertTrue(funsies.contains("troll-stack-traces"));
        assertEquals("Funsies", TransformerCatalog.Category.FUNSIES.display());
    }

    @Test
    void everyCategoryExplainsItsPurpose() {
        for (TransformerCatalog.Category category : TransformerCatalog.Category.values()) {
            assertFalse(category.description().isBlank(), category + " needs category guidance");
        }
    }

    private Set<String> names(TransformerCatalog.Category category) {
        return catalog.category(category).stream()
                .map(TransformerCatalog.Descriptor::name)
                .collect(Collectors.toSet());
    }
}
