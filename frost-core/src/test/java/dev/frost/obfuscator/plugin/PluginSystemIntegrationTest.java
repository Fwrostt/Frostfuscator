package dev.frost.obfuscator.plugin;

import dev.frost.api.FrostPlugin;
import dev.frost.api.PluginContext;
import dev.frost.api.event.Subscribe;
import dev.frost.api.event.events.PostObfuscationEvent;
import dev.frost.api.event.events.PreObfuscationEvent;
import dev.frost.api.transformer.ExecutionPass;
import dev.frost.api.transformer.PluginTransformer;
import dev.frost.api.transformer.TransformerCategory;
import dev.frost.api.transformer.TransformerContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginSystemIntegrationTest {

    static class SamplePlugin implements FrostPlugin {
        static final List<String> EVENTS_LOG = new ArrayList<>();
        static boolean transformerExecuted = false;

        @Override
        public void onLoad(PluginContext context) {
            context.registerTransformer(new PluginTransformer() {
                @Override
                public String id() {
                    return "sample:synthetic-class-generator";
                }

                @Override
                public String name() {
                    return "Synthetic Class Generator";
                }

                @Override
                public TransformerCategory category() {
                    return TransformerCategory.CUSTOM;
                }

                @Override
                public ExecutionPass pass() {
                    return ExecutionPass.PRE_PROCESSING;
                }

                @Override
                public void transform(TransformerContext context) {
                    ClassNode synthetic = new ClassNode();
                    synthetic.version = Opcodes.V17;
                    synthetic.access = Opcodes.ACC_PUBLIC;
                    synthetic.name = "com/example/PluginGeneratedClass";
                    synthetic.superName = "java/lang/Object";

                    context.addClass(synthetic);
                    context.addResource("plugin-resource.txt", "Created by Frostfuscator Plugin API".getBytes());
                    transformerExecuted = true;
                }
            });
        }

        @Subscribe
        public void onPreObfuscation(PreObfuscationEvent event) {
            EVENTS_LOG.add("PreObfuscationEvent");
        }

        @Subscribe
        public void onPostObfuscation(PostObfuscationEvent event) {
            EVENTS_LOG.add("PostObfuscationEvent");
        }
    }

    @Test
    void testPluginLifecycleAndEventBusIntegration() {
        SamplePlugin.EVENTS_LOG.clear();
        SamplePlugin.transformerExecuted = false;

        SamplePlugin plugin = new SamplePlugin();
        dev.frost.api.PluginDescriptor descriptor = new dev.frost.api.PluginDescriptor(
                "SamplePlugin", "1.0.0", SamplePlugin.class.getName(), "Sample plugin test", List.of("Tester"), List.of()
        );

        dev.frost.api.PluginLogger logger = new dev.frost.api.PluginLogger() {
            @Override public void info(String message, Object... args) {}
            @Override public void warn(String message, Object... args) {}
            @Override public void error(String message, Object... args) {}
            @Override public void debug(String message, Object... args) {}
            @Override public void trace(String message, Object... args) {}
        };

        PluginLoader.globalEventBus().registerListener(plugin);

        dev.frost.api.PluginContext apiContext = new dev.frost.api.PluginContext(
                descriptor, null, logger, PluginLoader.globalEventBus()
        );

        plugin.onLoad(apiContext);
        assertEquals(1, apiContext.registeredTransformers().size());

        // Trigger PreObfuscationEvent
        PreObfuscationEvent preEvent = PluginLoader.globalEventBus().post(
                new PreObfuscationEvent(null, new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>())
        );
        assertNotNull(preEvent);
        assertFalse(preEvent.isCancelled());

        // Run registered transformer
        dev.frost.api.transformer.PluginTransformer apiTransformer = apiContext.registeredTransformers().get(0);
        PluginTransformerAdapter adapter = new PluginTransformerAdapter(apiTransformer);
        assertEquals("Synthetic Class Generator", adapter.getName());
        assertEquals("CUSTOM", adapter.getCategory());

        // Trigger PostObfuscationEvent
        PostObfuscationEvent postEvent = PluginLoader.globalEventBus().post(
                new PostObfuscationEvent(null, new java.util.HashMap<>(), new java.util.HashMap<>())
        );
        assertNotNull(postEvent);

        // Verify lifecycle & event bus execution
        assertEquals(2, SamplePlugin.EVENTS_LOG.size());
        assertEquals("PreObfuscationEvent", SamplePlugin.EVENTS_LOG.get(0));
        assertEquals("PostObfuscationEvent", SamplePlugin.EVENTS_LOG.get(1));
    }
}
