package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LocalVariableRenameTransformerTest {
    @Test
    void keepsAValidNameWhenALateLocalWasNotCollected() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "example/LateLocal";
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.localVariables = new ArrayList<>();
        method.localVariables.add(new LocalVariableNode("collected", "I", null, start, end, 0));
        node.methods.add(method);

        ClassPool pool = new ClassPool() {
            @Override
            public <T> List<T> mapClasses(Function<? super ClassNode, T> operation) {
                List<T> collected = super.mapClasses(operation);
                method.localVariables.add(new LocalVariableNode("late", "I", null, start, end, 1));
                return collected;
            }
        };
        pool.addClass(node.name, node);

        new LocalVariableRenameTransformer().transform(pool, new MappingCollector(), new TransformerConfig());

        assertEquals("late", method.localVariables.get(1).name);
        assertTrue(method.localVariables.stream().allMatch(variable -> variable.name != null));
    }
}
