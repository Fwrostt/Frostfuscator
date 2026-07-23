package dev.frost.api.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigTest {

    @Test
    void testPluginConfigTypes() {
        Map<String, Object> map = Map.of(
                "enabled", true,
                "name", "my-plugin",
                "threads", 4,
                "packages", "com.example, org.sample",
                "nested", Map.of("secret", "abc")
        );

        PluginConfig config = new PluginConfig(map);

        assertTrue(config.getBoolean("enabled", false));
        assertEquals("my-plugin", config.getString("name", ""));
        assertEquals(4, config.getInt("threads", 1));
        assertEquals(List.of("com.example", "org.sample"), config.getStringList("packages"));

        PluginConfig nested = config.getSection("nested");
        assertEquals("abc", nested.getString("secret", ""));
    }
}
