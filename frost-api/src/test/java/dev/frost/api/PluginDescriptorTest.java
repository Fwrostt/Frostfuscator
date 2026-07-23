package dev.frost.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorTest {

    @Test
    void testPluginDescriptorDefaults() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "TestPlugin",
                "2.1.0",
                "com.example.TestPluginMain",
                "A test plugin",
                List.of("FrostTeam"),
                List.of("CorePlugin")
        );

        assertEquals("TestPlugin", descriptor.name());
        assertEquals("2.1.0", descriptor.version());
        assertEquals("com.example.TestPluginMain", descriptor.mainClass());
        assertEquals("A test plugin", descriptor.description());
        assertEquals(List.of("FrostTeam"), descriptor.authors());
        assertEquals(List.of("CorePlugin"), descriptor.dependencies());
        assertEquals(0, descriptor.priority());
    }

    @Test
    void testPluginDescriptorNullHandling() {
        PluginDescriptor descriptor = new PluginDescriptor(null, null, null, null, null, null);

        assertEquals("UnnamedPlugin", descriptor.name());
        assertEquals("1.0.0", descriptor.version());
        assertEquals("", descriptor.mainClass());
        assertEquals("", descriptor.description());
        assertTrue(descriptor.authors().isEmpty());
        assertTrue(descriptor.dependencies().isEmpty());
    }
}
