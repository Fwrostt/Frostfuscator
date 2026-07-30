package dev.frost.graph.bytecode;

import dev.frost.graph.*;
import dev.frost.graph.builder.GraphBuilder;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Printer;

import java.util.*;

/** Builds a true basic-block CFG, including exceptional flow, reachability, frames, and loop backedges. */
public final class ControlFlowGraphBuilder implements GraphBuilder<ControlFlowRequest> {
    public static final int MAX_ANALYZED_INSTRUCTIONS = 10_000;

    @Override public Graph build(ControlFlowRequest request, GraphBuildContext context) {
        byte[] bytes = request.project().bytesUnsafe(request.className());
        if (bytes == null) throw new IllegalArgumentException("Class not found: " + request.className());
        String bytecodeHash = GraphIds.hash(Base64.getEncoder().encodeToString(bytes), 24);
        String key = "cfg:" + request.className() + ":" + request.methodName() + ":"
                + request.descriptor() + ":" + bytecodeHash + ":" + context.options();
        var cached = context.cache().get(key); if (cached.isPresent()) return cached.get();

        MethodNode method = findMethod(bytes, request);
        if (method.instructions == null || method.instructions.size() == 0) {
            return new Graph(key, request.methodName(), GraphType.CONTROL_FLOW, List.of(), List.of(),
                    GraphMetadata.builder().put("class", request.className()).put("method", request.methodName())
                            .put("descriptor", method.desc).build(), List.of(), false);
        }
        if (method.instructions.size() > MAX_ANALYZED_INSTRUCTIONS) {
            Graph graph = oversizedMethodGraph(request, method, bytecodeHash, key);
            context.cache().put(key, graph);
            return graph;
        }
        Graph graph = buildMethod(request.className(), method, bytecodeHash, context, key);
        context.cache().put(key, graph);
        return graph;
    }

    private static Graph oversizedMethodGraph(ControlFlowRequest request, MethodNode method,
                                              String bytecodeHash, String key) {
        int instructionCount = method.instructions.size();
        GraphMetadata metadata = GraphMetadata.builder()
                .put("class", request.className()).put("method", method.name).put("descriptor", method.desc)
                .put("bytecodeHash", bytecodeHash).put("instructions", instructionCount)
                .put("analysisThreshold", MAX_ANALYZED_INSTRUCTIONS).put("pruned", true).build();
        GraphNode summary = new GraphNode(GraphIds.nodeId("cfg-pruned", key), "CFG pruned",
                NodeType.WARNING, metadata);
        GraphWarning warning = new GraphWarning(GraphWarning.Severity.WARNING, "instruction-threshold",
                "Control-flow analysis was pruned because the method exceeds "
                        + MAX_ANALYZED_INSTRUCTIONS + " instructions", metadata);
        return new Graph(key, request.className().replace('/', '.') + "." + method.name,
                GraphType.CONTROL_FLOW, List.of(summary), List.of(), metadata, List.of(warning), true);
    }

    private static MethodNode findMethod(byte[] bytes, ControlFlowRequest request) {
        List<MethodNode> matches = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals(request.methodName()) && (request.descriptor() == null || descriptor.equals(request.descriptor()))) {
                    MethodNode node = new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions);
                    matches.add(node);
                    return node;
                }
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);
        if (matches.isEmpty()) throw new IllegalArgumentException("Method not found: " + request.methodName()
                + (request.descriptor() == null ? "" : request.descriptor()));
        if (matches.size() > 1) throw new IllegalArgumentException("Method is overloaded; provide --descriptor");
        return matches.getFirst();
    }

    private static Graph buildMethod(String owner, MethodNode method, String bytecodeHash,
                                     GraphBuildContext context, String key) {
        AbstractInsnNode[] insns = method.instructions.toArray();
        Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        SortedSet<Integer> leaders = new TreeSet<>();
        int first = nextExecutable(insns, 0); if (first >= 0) leaders.add(first);
        for (int i = 0; i < insns.length; i++) {
            context.cancellation().throwIfCancelled();
            AbstractInsnNode insn = insns[i];
            if (insn instanceof JumpInsnNode jump) {
                addLeader(leaders, insns, index.get(jump.label));
                addLeader(leaders, insns, i + 1);
            } else if (insn instanceof TableSwitchInsnNode table) {
                addLeader(leaders, insns, index.get(table.dflt));
                table.labels.forEach(label -> addLeader(leaders, insns, index.get(label)));
                addLeader(leaders, insns, i + 1);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                addLeader(leaders, insns, index.get(lookup.dflt));
                lookup.labels.forEach(label -> addLeader(leaders, insns, index.get(label)));
                addLeader(leaders, insns, i + 1);
            } else if (isTerminal(insn.getOpcode())) {
                addLeader(leaders, insns, i + 1);
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            addLeader(leaders, insns, index.get(block.start));
            addLeader(leaders, insns, index.get(block.end));
            addLeader(leaders, insns, index.get(block.handler));
        }
        List<Integer> starts = new ArrayList<>(leaders);
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : insns.length;
            blocks.add(new Block(i, start, end));
        }

        List<PendingEdge> edges = new ArrayList<>();
        for (Block block : blocks) {
            int last = previousExecutable(insns, block.end - 1);
            if (last < block.start) continue;
            AbstractInsnNode terminal = insns[last];
            int opcode = terminal.getOpcode();
            if (terminal instanceof JumpInsnNode jump) {
                Block target = blockAt(blocks, nextExecutable(insns, index.get(jump.label)));
                if (opcode == Opcodes.GOTO || opcode == Opcodes.JSR) {
                    edge(edges, block, target, EdgeType.JUMP, "");
                } else {
                    edge(edges, block, target, EdgeType.CONDITIONAL_TRUE, "true");
                    edge(edges, block, blockAt(blocks, nextExecutable(insns, last + 1)), EdgeType.CONDITIONAL_FALSE, "false");
                }
            } else if (terminal instanceof TableSwitchInsnNode table) {
                edge(edges, block, blockAt(blocks, nextExecutable(insns, index.get(table.dflt))), EdgeType.SWITCH_DEFAULT, "default");
                for (int i = 0; i < table.labels.size(); i++) edge(edges, block,
                        blockAt(blocks, nextExecutable(insns, index.get(table.labels.get(i)))), EdgeType.SWITCH_CASE,
                        Integer.toString(table.min + i));
            } else if (terminal instanceof LookupSwitchInsnNode lookup) {
                edge(edges, block, blockAt(blocks, nextExecutable(insns, index.get(lookup.dflt))), EdgeType.SWITCH_DEFAULT, "default");
                for (int i = 0; i < lookup.labels.size(); i++) edge(edges, block,
                        blockAt(blocks, nextExecutable(insns, index.get(lookup.labels.get(i)))), EdgeType.SWITCH_CASE,
                        lookup.keys.get(i).toString());
            } else if (!isTerminal(opcode)) {
                edge(edges, block, blockAt(blocks, nextExecutable(insns, last + 1)), EdgeType.FALLTHROUGH, "");
            }
        }
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            int start = index.get(handler.start), end = index.get(handler.end);
            Block target = blockAt(blocks, nextExecutable(insns, index.get(handler.handler)));
            for (Block block : blocks) if (block.start < end && block.end > start) {
                edge(edges, block, target, EdgeType.EXCEPTION, handler.type == null ? "finally" : handler.type.replace('/', '.'));
            }
        }

        Set<Integer> reachable = reachable(edges, blocks.isEmpty() ? -1 : 0);
        Set<Integer> handlerBlocks = new HashSet<>();
        Map<Integer, List<String>> handlerTypes = new HashMap<>();
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            Block block = blockAt(blocks, nextExecutable(insns, index.get(handler.handler)));
            if (block != null) {
                handlerBlocks.add(block.number);
                handlerTypes.computeIfAbsent(block.number, ignored -> new ArrayList<>())
                        .add(handler.type == null ? "finally" : handler.type.replace('/', '.'));
            }
        }
        Frame<BasicValue>[] frames = analyze(owner, method);
        GraphCollector out = new GraphCollector(key, owner.replace('/', '.') + "." + method.name,
                GraphType.CONTROL_FLOW, context.options());
        for (Block block : blocks) {
            boolean live = reachable.contains(block.number);
            List<String> instructions = new ArrayList<>();
            for (int i = block.start; i < block.end; i++) if (insns[i].getOpcode() >= 0) {
                instructions.add(i + ": " + opcodeName(insns[i].getOpcode()));
            }
            GraphMetadata.Builder metadata = GraphMetadata.builder().put("index", block.number)
                    .put("startInstruction", block.start).put("endInstruction", block.end - 1)
                    .put("instructions", instructions).put("reachable", live)
                    .put("exceptionHandler", handlerBlocks.contains(block.number))
                    .put("handlerTypes", handlerTypes.getOrDefault(block.number, List.of()));
            int terminalIndex = previousExecutable(insns, block.end - 1);
            if (terminalIndex >= block.start) metadata.put("terminator", opcodeName(insns[terminalIndex].getOpcode()));
            if (frames != null && block.start < frames.length && frames[block.start] != null) {
                metadata.put("frameLocals", frames[block.start].getLocals()).put("frameStack", frames[block.start].getStackSize());
            }
            out.addNode(new GraphNode(block.id(owner, method), "Block " + block.number,
                    !live ? NodeType.UNREACHABLE_BLOCK
                            : handlerBlocks.contains(block.number) ? NodeType.EXCEPTION_HANDLER : NodeType.BASIC_BLOCK,
                    metadata.build()));
        }
        for (PendingEdge edge : edges) {
            out.addEdge(new GraphEdge(null, edge.from.id(owner, method), edge.to.id(owner, method), edge.type,
                    edge.label, GraphMetadata.EMPTY));
            if (edge.to.number <= edge.from.number) {
                out.addEdge(new GraphEdge(null, edge.from.id(owner, method), edge.to.id(owner, method), EdgeType.LOOP_BACK,
                        edge.label, GraphMetadata.builder().put("originalType", edge.type.name()).build()));
            }
        }
        if (frames == null) out.warning(new GraphWarning(GraphWarning.Severity.WARNING, "frame-analysis",
                "Stack frame analysis failed; control-flow edges are still available", GraphMetadata.EMPTY));
        long unreachable = blocks.stream().filter(block -> !reachable.contains(block.number)).count();
        out.metadata(GraphMetadata.builder().put("class", owner).put("method", method.name).put("descriptor", method.desc)
                .put("bytecodeHash", bytecodeHash).put("blocks", blocks.size()).put("unreachableBlocks", unreachable).build());
        return out.build();
    }

    private static Frame<BasicValue>[] analyze(String owner, MethodNode method) {
        try { return new Analyzer<>(new BasicInterpreter()).analyze(owner, method); }
        catch (AnalyzerException | RuntimeException ignored) { return null; }
    }
    private static Set<Integer> reachable(List<PendingEdge> edges, int first) {
        if (first < 0) return Set.of();
        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        for (PendingEdge edge : edges) adjacency.computeIfAbsent(edge.from.number, ignored -> new ArrayList<>()).add(edge.to.number);
        Set<Integer> seen = new LinkedHashSet<>(); ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(first);
        while (!queue.isEmpty()) { int item = queue.removeFirst(); if (!seen.add(item)) continue;
            queue.addAll(adjacency.getOrDefault(item, List.of())); }
        return seen;
    }
    private static void edge(List<PendingEdge> edges, Block from, Block to, EdgeType type, String label) {
        if (from != null && to != null) edges.add(new PendingEdge(from, to, type, label));
    }
    private static Block blockAt(List<Block> blocks, int instruction) {
        if (instruction < 0) return null;
        for (Block block : blocks) if (instruction >= block.start && instruction < block.end) return block;
        return null;
    }
    private static void addLeader(Set<Integer> leaders, AbstractInsnNode[] insns, int index) {
        int next = nextExecutable(insns, index); if (next >= 0) leaders.add(next);
    }
    private static int nextExecutable(AbstractInsnNode[] insns, int start) {
        for (int i = Math.max(0, start); i < insns.length; i++) if (insns[i].getOpcode() >= 0) return i;
        return -1;
    }
    private static int previousExecutable(AbstractInsnNode[] insns, int start) {
        for (int i = Math.min(start, insns.length - 1); i >= 0; i--) if (insns[i].getOpcode() >= 0) return i;
        return -1;
    }
    private static boolean isTerminal(int opcode) {
        return opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN
                || opcode == Opcodes.ATHROW || opcode == Opcodes.RET;
    }
    private static String opcodeName(int opcode) {
        return opcode >= 0 && opcode < Printer.OPCODES.length && Printer.OPCODES[opcode] != null
                ? Printer.OPCODES[opcode] : "OP_" + opcode;
    }
    private record Block(int number, int start, int end) {
        String id(String owner, MethodNode method) { return GraphIds.nodeId("block", owner + "." + method.name + method.desc + "#" + number); }
    }
    private record PendingEdge(Block from, Block to, EdgeType type, String label) {}

}
