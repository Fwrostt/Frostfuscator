package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeGraphBuilderTest {
    @Test void reusableIndexExposesSearchableClassesAndExactOverloads() {
        BytecodeProject project = new BytecodeProject(Map.of("fixture/Cfg", cfgClass()), Set.of());
        BytecodeProjectIndex index = project.index();
        BytecodeClassInfo info = index.findClass("fixture.Cfg").orElseThrow();
        assertEquals("fixture.Cfg", info.qualifiedName());
        assertEquals(Set.of("loop", "choice"), info.methods().stream().map(BytecodeMethodInfo::name)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals("loop(int)", info.methods().stream().filter(method -> method.name().equals("loop"))
                .findFirst().orElseThrow().displayName());
        assertSame(index, project.index(), "the expensive bytecode index is reused between graph types");
    }

    @Test void extractsInheritanceCallsInvokeDynamicCondyAndDescriptorReferences() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("fixture/A", classA());
        for (String name : List.of("fixture/Base", "fixture/I", "fixture/B", "fixture/C", "fixture/FieldType"))
            classes.put(name, emptyClass(name));
        BytecodeProject project = new BytecodeProject(classes, Set.of());
        GraphBuildContext context = new GraphBuildContext(new GraphOptions(500, 1000, 3, true, false, false,
                Set.of(), Set.of(), null, TraversalDirection.BOTH), GraphCancellation.NONE,
                GraphProgressListener.NONE, new GraphCache());
        Graph dependencies = new ClassDependencyGraphBuilder().build(project, context);
        Set<String> names = new HashSet<>(); dependencies.nodes().forEach(node -> names.add(node.metadata().string("internalName", "")));
        assertTrue(names.containsAll(Set.of("fixture/Base", "fixture/I", "fixture/B", "fixture/C", "fixture/FieldType")));
        Graph inheritance = new InheritanceGraphBuilder().build(project, context);
        assertTrue(inheritance.edges().stream().anyMatch(edge -> edge.type() == EdgeType.EXTENDS));
        assertTrue(inheritance.edges().stream().anyMatch(edge -> edge.type() == EdgeType.IMPLEMENTS));
        Graph calls = new MethodCallGraphBuilder().build(project, context);
        assertTrue(calls.edges().stream().anyMatch(edge -> edge.metadata().string("invocationKind", "").equals("invokedynamic")));
        assertTrue(calls.nodes().stream().anyMatch(node -> "fixture/B".equals(node.metadata().get("owner"))));
    }

    @Test void classFocusedCallsExposeIncomingAndOutgoingFlow() {
        BytecodeProject project = new BytecodeProject(Map.of(
                "fixture/A", classA(),
                "fixture/B", callerClass()), Set.of());
        GraphOptions options = new GraphOptions(500, 1_000, 2, false, false, false,
                Set.of(), Set.of(), "fixture/A", TraversalDirection.BOTH);
        Graph calls = new MethodCallGraphBuilder().build(project, new GraphBuildContext(options,
                GraphCancellation.NONE, GraphProgressListener.NONE, new GraphCache()));

        assertTrue(calls.nodes().stream().filter(node -> "fixture/A".equals(node.metadata().get("owner")))
                .allMatch(node -> node.metadata().bool("focus", false)));
        assertTrue(calls.edges().stream().anyMatch(edge -> "outgoing".equals(edge.metadata().get("flow"))));
        assertTrue(calls.edges().stream().anyMatch(edge -> "incoming".equals(edge.metadata().get("flow"))));
    }

    @Test void cfgHandlesLoopsSwitchTryCatchAndUnreachableCode() {
        BytecodeProject project = new BytecodeProject(Map.of("fixture/Cfg", cfgClass()), Set.of());
        GraphBuildContext context = GraphBuildContext.defaults();
        Graph loop = new ControlFlowGraphBuilder().build(new ControlFlowRequest(project, "fixture/Cfg", "loop", "(I)V"), context);
        assertTrue(loop.edges().stream().anyMatch(edge -> edge.type() == EdgeType.LOOP_BACK));
        assertTrue(loop.edges().stream().anyMatch(edge -> edge.type() == EdgeType.EXCEPTION));
        assertTrue(loop.nodes().stream().anyMatch(node -> node.type() == NodeType.UNREACHABLE_BLOCK));
        assertTrue(loop.nodes().stream().anyMatch(node -> node.type() == NodeType.EXCEPTION_HANDLER));
        assertTrue(loop.nodes().stream().allMatch(node -> node.metadata().get("instructions") != null));
        Graph choice = new ControlFlowGraphBuilder().build(new ControlFlowRequest(project, "fixture/Cfg", "choice", "(I)V"), context);
        assertTrue(choice.edges().stream().anyMatch(edge -> edge.type() == EdgeType.SWITCH_CASE));
        assertTrue(choice.edges().stream().anyMatch(edge -> edge.type() == EdgeType.SWITCH_DEFAULT));
    }

    @Test void cfgPrunesMethodsAboveTheInstructionSafetyThreshold() {
        BytecodeProject project = new BytecodeProject(Map.of("fixture/Huge", oversizedCfgClass()), Set.of());

        Graph graph = new ControlFlowGraphBuilder().build(
                new ControlFlowRequest(project, "fixture/Huge", "huge", "()V"),
                GraphBuildContext.defaults());

        assertTrue(graph.truncated());
        assertEquals(true, graph.metadata().get("pruned"));
        assertEquals(ControlFlowGraphBuilder.MAX_ANALYZED_INSTRUCTIONS,
                graph.metadata().get("analysisThreshold"));
        assertEquals(1, graph.nodes().size());
        assertTrue(graph.warnings().stream().anyMatch(warning -> warning.code().equals("instruction-threshold")));
    }

    private static byte[] classA() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/A", "Ljava/lang/Object;Lfixture/I;", "fixture/Base", new String[]{"fixture/I"});
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "Lfixture/FieldType;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "(Lfixture/C;)V", null, new String[]{"java/io/IOException"});
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/B", "foo", "()V", false);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/B", "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false);
        ConstantDynamic constant = new ConstantDynamic("constant", "Lfixture/C;",
                new Handle(Opcodes.H_INVOKESTATIC, "fixture/B", "constant", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false));
        method.visitInvokeDynamicInsn("dynamic", "()V", bootstrap, constant);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] callerClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/B", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "foo", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/A", "run", "(Lfixture/C;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static byte[] cfgClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Cfg", null, "java/lang/Object", null);
        MethodVisitor loop = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "loop", "(I)V", null, null);
        Label start = new Label(), body = new Label(), exit = new Label(), tryEnd = new Label(), handler = new Label(), done = new Label();
        loop.visitTryCatchBlock(start, tryEnd, handler, "java/lang/RuntimeException"); loop.visitCode(); loop.visitLabel(start);
        loop.visitVarInsn(Opcodes.ILOAD, 0); loop.visitJumpInsn(Opcodes.IFNE, body); loop.visitJumpInsn(Opcodes.GOTO, exit);
        loop.visitInsn(Opcodes.NOP); loop.visitInsn(Opcodes.RETURN); // deliberately unreachable block
        loop.visitLabel(body); loop.visitIincInsn(0, -1); loop.visitJumpInsn(Opcodes.GOTO, start);
        loop.visitLabel(exit); loop.visitInsn(Opcodes.RETURN); loop.visitLabel(tryEnd); loop.visitJumpInsn(Opcodes.GOTO, done);
        loop.visitLabel(handler); loop.visitInsn(Opcodes.POP); loop.visitInsn(Opcodes.RETURN); loop.visitLabel(done); loop.visitInsn(Opcodes.RETURN);
        loop.visitMaxs(0, 0); loop.visitEnd();
        MethodVisitor choice = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choice", "(I)V", null, null);
        Label zero = new Label(), one = new Label(), other = new Label(); choice.visitCode(); choice.visitVarInsn(Opcodes.ILOAD, 0);
        choice.visitTableSwitchInsn(0, 1, other, zero, one); choice.visitLabel(zero); choice.visitInsn(Opcodes.RETURN);
        choice.visitLabel(one); choice.visitInsn(Opcodes.RETURN); choice.visitLabel(other); choice.visitInsn(Opcodes.RETURN);
        choice.visitMaxs(0, 0); choice.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] oversizedCfgClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Huge", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "huge", "()V", null, null);
        method.visitCode();
        for (int index = 0; index <= ControlFlowGraphBuilder.MAX_ANALYZED_INSTRUCTIONS; index++) {
            method.visitInsn(Opcodes.NOP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
