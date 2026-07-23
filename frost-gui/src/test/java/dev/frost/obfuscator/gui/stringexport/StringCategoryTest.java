package dev.frost.obfuscator.gui.stringexport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StringCategoryTest {

    @Test
    void categorizesUrlsAndIps() {
        assertEquals(StringCategory.URL, StringCategory.categorize("https://example.com", 2.0, false, false));
        assertEquals(StringCategory.IP_ADDRESS, StringCategory.categorize("192.168.1.1", 2.0, false, false));
    }

    @Test
    void categorizesNewSecurityCategories() {
        assertEquals(StringCategory.JWT, StringCategory.categorize("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature", 4.0, false, false));
        assertEquals(StringCategory.API_KEY, StringCategory.categorize("sk-1234567890abcdef", 3.5, false, false));
        assertEquals(StringCategory.UUID, StringCategory.categorize("550e8400-e29b-41d4-a716-446655440000", 3.5, false, false));
        assertEquals(StringCategory.EMAIL, StringCategory.categorize("user@example.com", 2.5, false, false));
        assertEquals(StringCategory.COMMAND, StringCategory.categorize("/bin/bash", 2.0, false, false));
    }

    @Test
    void categorizesReflectionAndSql() {
        assertEquals(StringCategory.REFLECTION_TARGET, StringCategory.categorize("forName", 2.0, false, false));
        assertEquals(StringCategory.SQL, StringCategory.categorize("SELECT * FROM users WHERE id=1", 2.5, false, false));
    }

    @Test
    void categorizesEncodedAndHighEntropyStrings() {
        assertEquals(StringCategory.BASE64, StringCategory.categorize("VGhpcyBpcyBiYXNlNjQ=", 3.0, true, false));
        assertEquals(StringCategory.HEX, StringCategory.categorize("48656c6c6f", 2.5, false, true));
        assertEquals(StringCategory.HIGH_ENTROPY, StringCategory.categorize("xZ9pQ2vM8bK1wN7", 4.2, false, false));
    }

    @Test
    void categorizesResourcePathsAndFilePaths() {
        assertEquals(StringCategory.RESOURCE_PATH, StringCategory.categorize("META-INF/MANIFEST.MF", 2.0, false, false));
        assertEquals(StringCategory.RESOURCE_PATH, StringCategory.categorize("config/settings.properties", 2.0, false, false));
        assertEquals(StringCategory.FILE_PATH, StringCategory.categorize("/etc/hosts", 2.0, false, false));
    }

    @Test
    void categorizesFormatStringsAndJson() {
        assertEquals(StringCategory.FORMAT_STRING, StringCategory.categorize("Hello %s, you have %d items", 2.5, false, false));
        assertEquals(StringCategory.JSON_FRAGMENT, StringCategory.categorize("{\"key\": \"value\"}", 2.5, false, false));
    }

    @Test
    void categorizesLogMessages() {
        assertEquals(StringCategory.LOG_MESSAGE, StringCategory.categorize("[INFO] Application started successfully", 2.0, false, false));
    }
}
