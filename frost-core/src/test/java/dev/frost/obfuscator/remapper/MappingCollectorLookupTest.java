package dev.frost.obfuscator.remapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingCollectorLookupTest {
    @Test
    void performsConstantTimeOwnerAndUniqueNameLookups() {
        MappingCollector mappings = new MappingCollector();
        mappings.mapClass("sample/Original", "sample/a");
        mappings.mapMethod("sample/Original", "work", "()V", "b");
        mappings.mapField("sample/Original", "value", "I", "c");

        assertEquals("b", mappings.getMappedMethod("sample/a", "work", "()V"));
        assertEquals("c", mappings.getMappedField("sample/a", "value", "I"));
        assertEquals("b", mappings.getMappedMethodByName("work"));
        assertEquals("c", mappings.getMappedFieldByName("value"));
    }

    @Test
    void ambiguousReflectiveMemberNamesArePreserved() {
        MappingCollector mappings = new MappingCollector();
        mappings.mapMethod("sample/One", "run", "()V", "a");
        mappings.mapMethod("sample/Two", "run", "()V", "b");
        mappings.mapField("sample/One", "value", "I", "c");
        mappings.mapField("sample/Two", "value", "I", "d");

        assertEquals("run", mappings.getMappedMethodByName("run"));
        assertEquals("value", mappings.getMappedFieldByName("value"));
    }

    @Test
    void findsPreservedGeneratedMethodsAfterTheirOwnerIsRemapped() {
        MappingCollector mappings = new MappingCollector();
        mappings.preserveMethod("sample/Original", "fragment", "()Ljava/lang/String;");
        mappings.mapClass("sample/Original", "sample/a");

        assertTrue(mappings.isMethodPreserved("sample/a", "fragment", "()Ljava/lang/String;"));
    }
}
