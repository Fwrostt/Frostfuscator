package dev.frost.graph.bytecode;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.*;

/** Complete streaming reference extraction for graph builders. */
final class ClassReferences extends ClassVisitor {
    record MethodDeclaration(String name, String descriptor, int access) {}
    record MethodRef(String callerOwner, String callerName, String callerDescriptor,
                     String owner, String name, String descriptor, String kind) {}
    final Set<String> dependencies = new LinkedHashSet<>();
    final List<MethodRef> calls = new ArrayList<>();
    final List<MethodDeclaration> methods = new ArrayList<>();
    String name;
    String superName;
    List<String> interfaces = List.of();

    ClassReferences() { super(Opcodes.ASM9); }

    @Override public void visit(int version, int access, String name, String signature,
                                String superName, String[] interfaces) {
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        addInternal(superName);
        if (interfaces != null) for (String item : interfaces) addInternal(item);
        addSignature(signature);
    }

    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        addDescriptor(descriptor);
        return annotationVisitor();
    }

    @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
        addDescriptor(descriptor);
        return annotationVisitor();
    }

    @Override public void visitNestHost(String nestHost) { addInternal(nestHost); }
    @Override public void visitNestMember(String nestMember) { addInternal(nestMember); }
    @Override public void visitPermittedSubclass(String permittedSubclass) { addInternal(permittedSubclass); }
    @Override public void visitOuterClass(String owner, String name, String descriptor) {
        addInternal(owner); addMethodDescriptor(descriptor);
    }
    @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {
        addInternal(name); addInternal(outerName);
    }

    @Override public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
        addDescriptor(descriptor); addSignature(signature);
        return new RecordComponentVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
        };
    }

    @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        addDescriptor(descriptor);
        addSignature(signature);
        addConstant(value);
        return new FieldVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
        };
    }

    @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                               String signature, String[] exceptions) {
        methods.add(new MethodDeclaration(methodName, descriptor, access));
        addMethodDescriptor(descriptor);
        addSignature(signature);
        if (exceptions != null) for (String exception : exceptions) addInternal(exception);
        return new MethodVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath path, Label[] start,
                                                                             Label[] end, int[] index, String descriptor,
                                                                             boolean visible) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public void visitTypeInsn(int opcode, String type) { addInternal(type); }
            @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                addInternal(owner); addDescriptor(descriptor);
            }
            @Override public void visitMethodInsn(int opcode, String owner, String callee, String desc, boolean isInterface) {
                addInternal(owner); addMethodDescriptor(desc);
                calls.add(new MethodRef(ClassReferences.this.name, methodName, descriptor, owner, callee, desc,
                        isInterface ? "interface" : opcode == Opcodes.INVOKESTATIC ? "static" : "invoke"));
            }
            @Override public void visitInvokeDynamicInsn(String callee, String desc, Handle bootstrap, Object... args) {
                addMethodDescriptor(desc); addHandle(bootstrap);
                calls.add(new MethodRef(ClassReferences.this.name, methodName, descriptor,
                        bootstrap.getOwner(), bootstrap.getName(), bootstrap.getDesc(), "invokedynamic"));
                if (args != null) for (Object arg : args) { addConstant(arg); addConstantCall(arg, methodName, descriptor); }
            }
            @Override public void visitLdcInsn(Object value) { addConstant(value); addConstantCall(value, methodName, descriptor); }
            @Override public void visitMultiANewArrayInsn(String descriptor, int dims) { addDescriptor(descriptor); }
            @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { addInternal(type); }
            @Override public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                addDescriptor(descriptor); addSignature(signature);
            }
        };
    }

    private AnnotationVisitor annotationVisitor() {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { addConstant(value); }
            @Override public void visitEnum(String name, String descriptor, String value) { addDescriptor(descriptor); }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                addDescriptor(descriptor); return annotationVisitor();
            }
            @Override public AnnotationVisitor visitArray(String name) { return annotationVisitor(); }
        };
    }

    private void addConstant(Object value) {
        if (value instanceof Type type) addType(type);
        else if (value instanceof Handle handle) addHandle(handle);
        else if (value instanceof ConstantDynamic dynamic) {
            addDescriptor(dynamic.getDescriptor()); addHandle(dynamic.getBootstrapMethod());
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) addConstant(dynamic.getBootstrapMethodArgument(i));
        }
    }
    private void addConstantCall(Object value, String callerName, String callerDescriptor) {
        if (value instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
            calls.add(new MethodRef(name, callerName, callerDescriptor, handle.getOwner(), handle.getName(),
                    handle.getDesc(), "method-handle"));
        } else if (value instanceof ConstantDynamic dynamic) {
            Handle bootstrap = dynamic.getBootstrapMethod();
            calls.add(new MethodRef(name, callerName, callerDescriptor, bootstrap.getOwner(), bootstrap.getName(),
                    bootstrap.getDesc(), "condy-bootstrap"));
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
                addConstantCall(dynamic.getBootstrapMethodArgument(i), callerName, callerDescriptor);
        }
    }
    private void addHandle(Handle handle) {
        addInternal(handle.getOwner());
        if (handle.getDesc().startsWith("(")) addMethodDescriptor(handle.getDesc()); else addDescriptor(handle.getDesc());
    }
    private void addMethodDescriptor(String descriptor) {
        if (descriptor == null) return;
        try {
            for (Type type : Type.getArgumentTypes(descriptor)) addType(type);
            addType(Type.getReturnType(descriptor));
        } catch (IllegalArgumentException ignored) { }
    }
    private void addDescriptor(String descriptor) {
        if (descriptor == null) return;
        try { addType(Type.getType(descriptor)); } catch (IllegalArgumentException ignored) { }
    }
    private void addType(Type type) {
        if (type == null) return;
        if (type.getSort() == Type.ARRAY) addType(type.getElementType());
        else if (type.getSort() == Type.OBJECT) addInternal(type.getInternalName());
        else if (type.getSort() == Type.METHOD) addMethodDescriptor(type.getDescriptor());
    }
    private void addSignature(String signature) {
        if (signature == null) return;
        try {
            SignatureVisitor visitor = new SignatureVisitor(Opcodes.ASM9) {
                private String current;
                @Override public void visitClassType(String name) { current = name; addInternal(name); }
                @Override public void visitInnerClassType(String name) {
                    current = current == null ? name : current + "$" + name; addInternal(current);
                }
            };
            try { new SignatureReader(signature).accept(visitor); }
            catch (IllegalArgumentException classOrMethodSignature) { new SignatureReader(signature).acceptType(visitor); }
        } catch (IllegalArgumentException ignored) { }
    }
    private void addInternal(String internalName) {
        if (internalName != null && !internalName.isBlank()) dependencies.add(internalName);
    }
}
