package dev.frost.api.transformer;

import dev.frost.api.PluginLogger;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;

/**
 * Provides access to class pool, resources, logger, and configuration during transformer execution.
 */
public interface TransformerContext {

    /**
     * @return mutable map of internal class names ("com/example/MyClass") to ASM ClassNodes
     */
    Map<String, ClassNode> classPool();

    /**
     * @return mutable map of resource entry names ("META-INF/MANIFEST.MF") to raw bytes
     */
    Map<String, byte[]> resourcePool();

    /**
     * @return plugin logger instance
     */
    PluginLogger logger();

    /**
     * @return map of transformer configuration properties
     */
    Map<String, Object> config();

    /**
     * Adds a new class node to the class pool.
     */
    void addClass(ClassNode classNode);

    /**
     * Removes a class node by internal name.
     */
    void removeClass(String internalName);

    /**
     * Adds or updates a resource entry in the output archive.
     */
    void addResource(String path, byte[] data);

    /**
     * Removes a resource entry by path.
     */
    void removeResource(String path);
}
