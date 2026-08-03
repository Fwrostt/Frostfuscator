package dev.frost.ir.analysis;

public final class StandardAnalyses {
    private StandardAnalyses() {}

    public static final MethodAnalysis<DominatorTree> DOMINATORS = new MethodAnalysis<>() {
        private final AnalysisKey<DominatorTree> key = new AnalysisKey<>("frost.dominators", DominatorTree.class);
        @Override public AnalysisKey<DominatorTree> key() { return key; }
        @Override public DominatorTree compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return DominatorTree.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        }
    };

    public static final MethodAnalysis<PostDominatorTree> POST_DOMINATORS = new MethodAnalysis<>() {
        private final AnalysisKey<PostDominatorTree> key = new AnalysisKey<>("frost.post-dominators", PostDominatorTree.class);
        @Override public AnalysisKey<PostDominatorTree> key() { return key; }
        @Override public PostDominatorTree compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return PostDominatorTree.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        }
    };

    public static final MethodAnalysis<LoopInfo> LOOPS = new MethodAnalysis<>() {
        private final AnalysisKey<LoopInfo> key = new AnalysisKey<>("frost.loops", LoopInfo.class);
        @Override public AnalysisKey<LoopInfo> key() { return key; }
        @Override public LoopInfo compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return LoopInfo.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        }
    };

    public static final MethodAnalysis<Liveness> LIVENESS = new MethodAnalysis<>() {
        private final AnalysisKey<Liveness> key = new AnalysisKey<>("frost.liveness", Liveness.class);
        @Override public AnalysisKey<Liveness> key() { return key; }
        @Override public Liveness compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return Liveness.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        }
    };

    public static final MethodAnalysis<SparseConditionalConstants> SCCP = new MethodAnalysis<>() {
        private final AnalysisKey<SparseConditionalConstants> key = new AnalysisKey<>("frost.sccp", SparseConditionalConstants.class);
        @Override public AnalysisKey<SparseConditionalConstants> key() { return key; }
        @Override public SparseConditionalConstants compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return SparseConditionalConstants.compute(method);
        }
    };

    public static final MethodAnalysis<GlobalValueNumbering> GVN = new MethodAnalysis<>() {
        private final AnalysisKey<GlobalValueNumbering> key = new AnalysisKey<>("frost.gvn", GlobalValueNumbering.class);
        @Override public AnalysisKey<GlobalValueNumbering> key() { return key; }
        @Override public GlobalValueNumbering compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return GlobalValueNumbering.compute(method);
        }
    };

    public static final MethodAnalysis<AliasAnalysis> ALIAS = new MethodAnalysis<>() {
        private final AnalysisKey<AliasAnalysis> key = new AnalysisKey<>("frost.alias", AliasAnalysis.class);
        @Override public AnalysisKey<AliasAnalysis> key() { return key; }
        @Override public AliasAnalysis compute(dev.frost.ir.model.IrMethod method, AnalysisManager analyses) {
            return AliasAnalysis.compute(method, analyses.get(method, SCCP));
        }
    };

    public static final MethodAnalysis<EscapeAnalysis> ESCAPE = new MethodAnalysis<>() {
        private final AnalysisKey<EscapeAnalysis> key = new AnalysisKey<>("frost.escape", EscapeAnalysis.class);
        @Override public AnalysisKey<EscapeAnalysis> key() { return key; }
        @Override public EscapeAnalysis compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return EscapeAnalysis.compute(method);
        }
    };

    public static final MethodAnalysis<MemorySSA> MEMORY_SSA = new MethodAnalysis<>() {
        private final AnalysisKey<MemorySSA> key = new AnalysisKey<>("frost.memory-ssa", MemorySSA.class);
        @Override public AnalysisKey<MemorySSA> key() { return key; }
        @Override public MemorySSA compute(dev.frost.ir.model.IrMethod method, AnalysisManager analyses) {
            return MemorySSA.compute(method, analyses.get(method, ALIAS));
        }
    };

    public static final MethodAnalysis<NullnessAnalysis> NULLNESS = new MethodAnalysis<>() {
        private final AnalysisKey<NullnessAnalysis> key = new AnalysisKey<>("frost.nullness", NullnessAnalysis.class);
        @Override public AnalysisKey<NullnessAnalysis> key() { return key; }
        @Override public NullnessAnalysis compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return NullnessAnalysis.compute(method);
        }
    };

    public static final MethodAnalysis<IntegerRangeAnalysis> INTEGER_RANGES = new MethodAnalysis<>() {
        private final AnalysisKey<IntegerRangeAnalysis> key = new AnalysisKey<>("frost.integer-ranges", IntegerRangeAnalysis.class);
        @Override public AnalysisKey<IntegerRangeAnalysis> key() { return key; }
        @Override public IntegerRangeAnalysis compute(dev.frost.ir.model.IrMethod method, AnalysisManager ignored) {
            return IntegerRangeAnalysis.compute(method);
        }
    };
}
