package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BuildStateReleaseTest {

    @Test
    void releasesClassGraphsHierarchyIndexesAndJarBuffers() {
        ClassPool pool = new ClassPool();
        ClassNode application = classNode("sample/Application");
        ClassNode library = classNode("sample/Library");
        pool.addClass(application.name, application);
        pool.addLibraryClass(library.name, library);
        pool.markDirty(application.name);
        pool.markGeneratedDecoy(application.name);
        pool.excludeFromTransformation(application.name, "test");
        pool.buildHierarchy();

        JarProcessor processor = new JarProcessor();
        processor.putResource("sample/resource.bin", new byte[1024]);

        pool.releaseBuildState();
        processor.releaseBuildState();

        assertEquals(0, pool.size());
        assertEquals(0, pool.librarySize());
        assertEquals(0, pool.transformationExcludedSize());
        assertFalse(pool.getHierarchy().isInPool(application.name));
        assertFalse(pool.isDirty(application.name));
        assertFalse(pool.isGeneratedDecoy(application.name));
        assertEquals(0, processor.getResources().size());
        assertEquals(0, processor.getOriginalClassBytes().size());
    }

    private ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }
}
