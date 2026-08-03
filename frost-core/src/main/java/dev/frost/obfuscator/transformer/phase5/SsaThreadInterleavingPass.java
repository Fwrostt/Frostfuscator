package dev.frost.obfuscator.transformer.phase5;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.Value;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.UninitializedType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** Splits the two pure SSA def-use branches of one arithmetic operation across joined workers. */
public final class SsaThreadInterleavingPass implements MethodPass {
    private static final Set<OperationCode> COMBINERS = Set.of(
            CoreOps.ADD, CoreOps.SUB, CoreOps.MUL, CoreOps.AND, CoreOps.OR, CoreOps.XOR,
            CoreOps.SHL, CoreOps.SHR, CoreOps.USHR
    );
    private static final String FUTURE = "java/util/concurrent/CompletableFuture";

    private final int ownerVersion;
    private final String leftWorkerName;
    private final String rightWorkerName;
    private final int probability;
    private final int minimumBranchInstructions;
    private final int minimumExpressionInstructions;
    private final int maximumExpressionInstructions;
    private final int maximumCaptureSlots;
    private List<ClassNode> workers = List.of();

    public SsaThreadInterleavingPass(int ownerVersion, String leftWorkerName, String rightWorkerName,
                                     int probability, int minimumBranchInstructions,
                                     int minimumExpressionInstructions, int maximumExpressionInstructions,
                                     int maximumCaptureSlots) {
        this.ownerVersion = ownerVersion;
        this.leftWorkerName = leftWorkerName;
        this.rightWorkerName = rightWorkerName;
        this.probability = Math.max(0, Math.min(100, probability));
        this.minimumBranchInstructions = Math.max(1, minimumBranchInstructions);
        this.minimumExpressionInstructions = Math.max(3, minimumExpressionInstructions);
        this.maximumExpressionInstructions = Math.max(this.minimumExpressionInstructions, maximumExpressionInstructions);
        this.maximumCaptureSlots = Math.max(1, maximumCaptureSlots);
    }

    @Override
    public String id() {
        return "phase5.thread-interleaving";
    }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        if (!method.exceptionRegions().isEmpty() || probability == 0) return PassResult.unchanged();
        SplittableRandom random = context.randomFor(id());
        Candidate candidate = select(method, random);
        if (candidate == null) return PassResult.unchanged();

        ClassNode leftWorker = worker(leftWorkerName, candidate.left);
        ClassNode rightWorker = worker(rightWorkerName, candidate.right);
        BasicBlock block = candidate.combiner.block().orElseThrow();
        List<IrInstruction> dispatch = new ArrayList<>();
        WorkerValues left = appendWorkerDispatch(method, dispatch, leftWorkerName, candidate.left, "$r");
        WorkerValues right = appendWorkerDispatch(method, dispatch, rightWorkerName, candidate.right, "$r");
        dispatch.add(invoke(method, FUTURE, "join", "()Ljava/lang/Object;", "INVOKEVIRTUAL", false,
                List.of(left.future), List.of(ReferenceType.OBJECT)));
        dispatch.add(invoke(method, FUTURE, "join", "()Ljava/lang/Object;", "INVOKEVIRTUAL", false,
                List.of(right.future), List.of(ReferenceType.OBJECT)));
        IrInstruction leftResult = fieldLoad(method, left.worker, leftWorkerName, "$r", candidate.left.root().type());
        IrInstruction rightResult = fieldLoad(method, right.worker, rightWorkerName, "$r", candidate.right.root().type());
        dispatch.add(leftResult);
        dispatch.add(rightResult);
        IrInstruction combined = method.createInstruction(candidate.combiner.operation(),
                List.of(leftResult.result(), rightResult.result()), List.of(candidate.combiner.result().type()));
        dispatch.add(combined);

        int insertion = block.instructions().indexOf(candidate.combiner);
        for (IrInstruction instruction : dispatch) block.insert(insertion++, instruction);
        candidate.combiner.result().replaceAllUsesWith(combined.result());
        candidate.combiner.erase();
        int erased = candidate.left.eraseDeadDefinitions() + candidate.right.eraseDeadDefinitions();
        workers = List.of(leftWorker, rightWorker);
        return new PassResult(true, PreservedAnalyses.none(), List.of(),
                Map.of("expressions", 1L, "workers", 2L, "erasedOperations", (long) erased));
    }

    public List<ClassNode> workers() {
        return workers;
    }

    private Candidate select(IrMethod method, SplittableRandom random) {
        Candidate best = null;
        for (BasicBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (!COMBINERS.contains(instruction.operation().code()) || instruction.operands().size() != 2
                        || instruction.results().size() != 1 || !instruction.result().isUsed()
                        || random.nextInt(100) >= probability) continue;
                PhaseFiveExpression.Tree left = PhaseFiveExpression.build(
                        instruction.operands().get(0), maximumExpressionInstructions).orElse(null);
                PhaseFiveExpression.Tree right = PhaseFiveExpression.build(
                        instruction.operands().get(1), maximumExpressionInstructions).orElse(null);
                if (left == null || right == null || left.size() < minimumBranchInstructions
                        || right.size() < minimumBranchInstructions
                        || left.size() + right.size() + 1 < minimumExpressionInstructions
                        || left.captureSlots() > maximumCaptureSlots
                        || right.captureSlots() > maximumCaptureSlots) continue;
                Candidate value = new Candidate(instruction, left, right);
                if (best == null || value.complexity() > best.complexity()) best = value;
            }
        }
        return best;
    }

    private WorkerValues appendWorkerDispatch(IrMethod method, List<IrInstruction> output,
                                               String workerName, PhaseFiveExpression.Tree expression,
                                               String resultField) {
        ReferenceType workerType = new ReferenceType(workerName, Nullability.NON_NULL);
        IrInstruction allocation = method.createInstruction(
                new Operation(CoreOps.NEW_OBJECT, Map.of("type", new IrAttribute.TypeValue(workerType))),
                List.of(), id -> List.of(new UninitializedType(id, workerType)));
        output.add(allocation);
        List<Value> constructorOperands = new ArrayList<>();
        constructorOperands.add(allocation.result());
        constructorOperands.addAll(expression.captures());
        IrInstruction initialized = method.createInstruction(
                memberOperation(CoreOps.INITIALIZE, workerName, "<init>", constructorDescriptor(expression),
                        "INVOKESPECIAL", false), constructorOperands, List.of(workerType));
        output.add(initialized);
        IrInstruction future = invoke(method, FUTURE, "runAsync",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", "INVOKESTATIC", false,
                List.of(initialized.result()),
                List.of(new ReferenceType(FUTURE, Nullability.NON_NULL)));
        output.add(future);
        return new WorkerValues(initialized.result(), future.result());
    }

    private IrInstruction fieldLoad(IrMethod method, Value receiver, String owner, String name,
                                    dev.frost.ir.type.IrType type) {
        return method.createInstruction(memberOperation(CoreOps.FIELD_LOAD, owner, name,
                type.displayName(), "", false), List.of(receiver), List.of(type));
    }

    private IrInstruction invoke(IrMethod method, String owner, String name, String descriptor,
                                 String kind, boolean itf, List<Value> operands,
                                 List<dev.frost.ir.type.IrType> results) {
        return method.createInstruction(memberOperation(CoreOps.INVOKE, owner, name, descriptor, kind, itf),
                operands, results);
    }

    private Operation memberOperation(OperationCode code, String owner, String name, String descriptor,
                                      String kind, boolean itf) {
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        attributes.put("owner", new IrAttribute.StringValue(owner));
        attributes.put("name", new IrAttribute.StringValue(name));
        attributes.put("descriptor", new IrAttribute.StringValue(descriptor));
        if (!kind.isEmpty()) attributes.put("invoke_kind", new IrAttribute.StringValue(kind));
        attributes.put("interface", new IrAttribute.BooleanValue(itf));
        return new Operation(code, attributes);
    }

    private ClassNode worker(String name, PhaseFiveExpression.Tree expression) {
        ClassNode node = new ClassNode();
        node.version = Math.max(Opcodes.V1_8, ownerVersion);
        node.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        node.name = name;
        node.superName = "java/lang/Object";
        node.interfaces = new ArrayList<>(List.of("java/lang/Runnable"));
        for (int index = 0; index < expression.captures().size(); index++) {
            Value capture = expression.captures().get(index);
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                    "$c" + index, capture.type().displayName(), null, null));
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                "$r", expression.root().type().displayName(), null, null));
        node.methods.add(workerConstructor(name, expression));
        node.methods.add(workerRun(name, expression));
        return node;
    }

    private MethodNode workerConstructor(String owner, PhaseFiveExpression.Tree expression) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "<init>", constructorDescriptor(expression), null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        int local = 1;
        for (int index = 0; index < expression.captures().size(); index++) {
            Value capture = expression.captures().get(index);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new VarInsnNode(capture.type() == dev.frost.ir.type.PrimitiveType.LONG
                    ? Opcodes.LLOAD : Opcodes.ILOAD, local));
            method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "$c" + index,
                    capture.type().displayName()));
            local += capture.type().slots();
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = local;
        method.maxStack = 4;
        return method;
    }

    private MethodNode workerRun(String owner, PhaseFiveExpression.Tree expression) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "run", "()V", null, null);
        int local = 1;
        for (int index = 0; index < expression.captures().size(); index++) {
            Value capture = expression.captures().get(index);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "$c" + index,
                    capture.type().displayName()));
            method.instructions.add(new VarInsnNode(capture.type() == dev.frost.ir.type.PrimitiveType.LONG
                    ? Opcodes.LSTORE : Opcodes.ISTORE, local));
            local += capture.type().slots();
        }
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        expression.emit(method.instructions, 1);
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "$r",
                expression.root().type().displayName()));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = local;
        method.maxStack = Math.max(6, expression.size() + 2);
        return method;
    }

    private String constructorDescriptor(PhaseFiveExpression.Tree expression) {
        String descriptor = expression.descriptor();
        return descriptor.substring(0, descriptor.indexOf(')') + 1) + 'V';
    }

    private record Candidate(IrInstruction combiner, PhaseFiveExpression.Tree left,
                             PhaseFiveExpression.Tree right) {
        private int complexity() { return left.size() + right.size() + 1; }
    }

    private record WorkerValues(Value worker, Value future) {}
}
