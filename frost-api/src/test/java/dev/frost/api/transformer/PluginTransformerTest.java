package dev.frost.api.transformer;

import dev.frost.api.PluginLogger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginTransformerTest {

    static class MockTransformerContext implements TransformerContext {
        final Map<String, ClassNode> classes = new HashMap<>();
        final Map<String, byte[]> resources = new HashMap<>();
        final Map<String, Object> cfg = new HashMap<>();

        @Override
        public Map<String, ClassNode> classPool() {
            return classes;
        }

        @Override
        public Map<String, byte[]> resourcePool() {
            return resources;
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override public void info(String message, Object... args) {}
                @Override public void warn(String message, Object... args) {}
                @Override public void error(String message, Object... args) {}
                @Override public void debug(String message, Object... args) {}
                @Override public void trace(String message, Object... args) {}
            };
        }

        @Override
        public Map<String, Object> config() {
            return cfg;
        }

        @Override
        public void addClass(ClassNode classNode) {
            classes.put(classNode.name, classNode);
        }

        @Override
        public void removeClass(String internalName) {
            classes.remove(internalName);
        }

        @Override
        public void addResource(String path, byte[] data) {
            resources.put(path, data);
        }

        @Override
        public void removeResource(String path) {
            resources.remove(path);
        }
    }

    @Test
    void testCustomTransformerExecution() {
        PluginTransformer transformer = new PluginTransformer() {
            @Override
            public String id() {
                return "test:dummy";
            }

            @Override
            public String name() {
                return "Dummy Transformer";
            }

            @Override
            public void transform(TransformerContext context) {
                ClassNode node = new ClassNode();
                node.name = "com/example/SyntheticClass";
                context.addClass(node);
                context.addResource("dummy.txt", "hello".getBytes());
            }
        };

        MockTransformerContext context = new MockTransformerContext();
        transformer.transform(context);

        assertTrue(context.classes.containsKey("com/example/SyntheticClass"));
        assertTrue(context.resources.containsKey("dummy.txt"));
        assertEquals(ExecutionPass.PRIMARY, transformer.pass());
        assertEquals(TransformerCategory.CUSTOM, transformer.category());
    }
}
