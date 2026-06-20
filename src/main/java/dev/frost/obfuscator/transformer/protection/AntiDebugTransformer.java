package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class AntiDebugTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "anti-debug";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public void transform(Context context) {
        String methodName = context.config().getOption("method-name", "__frost$antiDebug");
        boolean checkArguments = getBooleanOption(context, "check-arguments", true);
        boolean checkDebugClasses = getBooleanOption(context, "check-debug-classes", true);
        boolean checkStack = getBooleanOption(context, "check-stack", true);
        boolean checkTiming = getBooleanOption(context, "check-timing", true);
        boolean checkProcesses = getBooleanOption(context, "check-processes", false);
        boolean sharedHelper = getBooleanOption(context, "shared-helper", true);
        int timingIterations = getIntOption(context, "timing-iterations", 1_000_000);
        long timingThresholdNanos = Math.max(1L, getIntOption(context, "timing-threshold-ms", 80)) * 1_000_000L;
        int injected = 0;
        int guardedMethods = 0;
        String helperClass = sharedHelper ? resolveHelperClass(context) : null;

        if (sharedHelper && !context.pool().contains(helperClass)) {
            ClassNode helper = new ClassNode();
            helper.version = Opcodes.V17;
            helper.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
            helper.name = helperClass;
            helper.superName = "java/lang/Object";
            MethodNode guard = buildGuard(methodName, checkArguments, checkDebugClasses, checkStack,
                    checkTiming, checkProcesses, timingIterations, timingThresholdNanos);
            guard.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            helper.methods.add(guard);
            context.pool().addClass(helperClass, helper);
            context.pool().markDirty(helperClass);
            injected++;
        }

        for (ClassNode classNode : context.pool().getClasses()) {
            if (classNode.name.equals(helperClass)) {
                continue;
            }
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                continue;
            }

            if (!sharedHelper && hasMethod(classNode, methodName)) {
                continue;
            }

            if (!sharedHelper) {
                classNode.methods.add(buildGuard(methodName, checkArguments, checkDebugClasses, checkStack,
                        checkTiming, checkProcesses, timingIterations, timingThresholdNanos));
            }
            int classGuards = injectGuards(classNode, sharedHelper ? helperClass : classNode.name, methodName);
            if (classGuards == 0) {
                continue;
            }
            context.pool().markDirty(classNode.name);
            if (!sharedHelper) {
                injected++;
            }
            guardedMethods += classGuards;
        }

        context.stats().add("antiDebugHooks", injected);
        context.stats().add("antiDebugGuardedMethods", guardedMethods);
        log("Injected {} anti-debug hooks across {} methods", injected, guardedMethods);
    }

    private int injectGuards(ClassNode classNode, String guardOwner, String guardName) {
        int injected = 0;
        for (MethodNode method : classNode.methods) {
            if (shouldGuard(method, guardName)) {
                method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, guardOwner, guardName, "()V", false));
                injected++;
            }
        }
        return injected;
    }

    private boolean shouldGuard(MethodNode method, String guardName) {
        if (guardName.equals(method.name)
                || "<init>".equals(method.name)
                || "<clinit>".equals(method.name)
                || method.instructions == null
                || method.instructions.size() == 0) {
            return false;
        }
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private boolean hasMethod(ClassNode classNode, String name) {
        return classNode.methods.stream().anyMatch(method -> name.equals(method.name) && "()V".equals(method.desc));
    }

    private MethodNode buildGuard(String name,
                                  boolean checkArguments,
                                  boolean checkDebugClasses,
                                  boolean checkStack,
                                  boolean checkTiming,
                                  boolean checkProcesses,
                                  int timingIterations,
                                  long timingThresholdNanos) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "()V",
                null,
                null
        );
        LabelNode fail = new LabelNode();
        InsnList insns = method.instructions;

        if (checkArguments) {
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/management/ManagementFactory", "getRuntimeMXBean", "()Ljava/lang/management/RuntimeMXBean;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/management/RuntimeMXBean", "getInputArguments", "()Ljava/util/List;", true));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false));
            addStringContainsAny(insns, fail, "jdwp", "-xdebug", "-javaagent");
        }

        if (checkDebugClasses) {
            addClassPresenceCheck(method, insns, fail, "com.intellij.rt.debugger.agent.CaptureAgent");
            addClassPresenceCheck(method, insns, fail, "com.intellij.rt.debugger.agent.DebuggerAgent");
            addClassPresenceCheck(method, insns, fail, "org.eclipse.jdt.internal.debug.core.JDIDebugPlugin");
        }

        if (checkStack) {
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false));
            addStringContainsAny(insns, fail, "debugger", "jdwp", "jdb", "idea_rt", "eclipse");
        }

        if (checkTiming) {
            addTimingCheck(insns, fail, timingIterations, timingThresholdNanos);
        }

        if (checkProcesses) {
            addProcessCheck(method, insns, fail);
        }

        insns.add(new InsnNode(Opcodes.RETURN));
        insns.add(fail);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("Debugging is disabled"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        method.maxStack = 6;
        method.maxLocals = 8;
        return method;
    }

    private String resolveHelperClass(Context context) {
        String configured = context.config().getOption("helper-class", "");
        if (configured != null && !configured.isBlank()) {
            return configured.replace('.', '/').replaceAll("[^\\p{L}\\p{N}_$/]", "");
        }
        return "__frost/" + randomIdentifier(10);
    }

    private void addStringContainsAny(InsnList insns, LabelNode fail, String... needles) {
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));
        for (String needle : needles) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
            insns.add(new LdcInsnNode(needle));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false));
            insns.add(new JumpInsnNode(Opcodes.IFNE, fail));
        }
    }

    private void addClassPresenceCheck(MethodNode method, InsnList insns, LabelNode fail, String className) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        insns.add(start);
        insns.add(new LdcInsnNode(className));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(end);
        insns.add(new JumpInsnNode(Opcodes.GOTO, fail));
        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, 0));
        insns.add(done);
    }

    private void addTimingCheck(InsnList insns, LabelNode fail, int iterations, long thresholdNanos) {
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 0));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new LdcInsnNode(Math.max(1, iterations)));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insns.add(new IincInsnNode(2, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(done);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 0));
        insns.add(new InsnNode(Opcodes.LSUB));
        insns.add(new LdcInsnNode(thresholdNanos));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFGT, fail));
    }

    private void addProcessCheck(MethodNode method, InsnList insns, LabelNode fail) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode done = new LabelNode();
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        insns.add(start);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/ProcessHandle", "allProcesses", "()Ljava/util/stream/Stream;", true));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/BaseStream", "iterator", "()Ljava/util/Iterator;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, end));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/ProcessHandle"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/ProcessHandle", "info", "()Ljava/lang/ProcessHandle$Info;", true));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/ProcessHandle$Info", "command", "()Ljava/util/Optional;", true));
        insns.add(new LdcInsnNode(""));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false));
        addStringContainsAny(insns, fail, "x64dbg", "ida.exe", "ida64", "ghidra", "jd-gui", "recaf", "bytecode-viewer");
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(end);
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, 4));
        insns.add(done);
    }

    private boolean getBooleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private int getIntOption(Context context, String key, int fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private String randomIdentifier(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String body = alphabet + "0123456789";
        StringBuilder builder = new StringBuilder(length);
        builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        for (int i = 1; i < length; i++) {
            builder.append(body.charAt(RANDOM.nextInt(body.length())));
        }
        return builder.toString();
    }
}
