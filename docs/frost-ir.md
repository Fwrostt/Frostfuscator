# Frost-IR architecture

Frost-IR is Frostfuscator's Java 21 compiler substrate. It deliberately lives in a dedicated
`frost-ir` module: bytecode adapters depend on ASM, but the model, analyses, transformations,
and validation APIs do not depend on the obfuscation engine or GUI.

## Design commitments

1. **SSA and CFG are one consistency domain.** A method owns every block, edge, instruction,
   value, phi, exception region, and source mapping. Cross-method references are invalid. CFG
   edges are first-class objects so phi inputs can distinguish parallel switch and exceptional
   edges.
2. **The JVM is modeled, not disguised.** Frost types retain verifier states such as null,
   uninitialized objects, category-2 values, and return addresses. Operations carry explicit
   effect summaries for heap, static, monitor, allocation, invocation, and exceptional behavior.
3. **Phi nodes have edge identity.** A phi input is keyed by the incoming `ControlEdge`, not only
   the predecessor block. This is necessary for parallel edges, exception handlers, and later
   edge splitting. Phis have simultaneous/parallel-copy semantics.
4. **Use-def chains are structural.** Each operand is a stable `Use` object. Replacing an operand
   updates both the instruction and value def-use views atomically. Definitions never change.
5. **Mutation is explicit and revisioned.** Mutable methods are optimized in place through
   ownership-checking APIs. Every semantic mutation advances the method revision and invalidates
   analyses not declared preserved by a pass. Frozen snapshots are immutable, deterministic,
   serializable views suitable for caches, diagnostics, and plugin boundaries.
6. **Operations are extensible without subclass proliferation.** Built-in JVM-independent Frost
   operations use stable namespaced opcodes. Dialects may register additional operation schemas,
   validators, printers, effect models, and lowerers in an `IrContext`.
7. **Import and lowering are transactions.** ASM nodes never become the IR. Import produces a
   source map in both directions and preserves class-file-only metadata. Lowering targets a fresh
   `MethodNode`, reconstructs locals/stack/labels/frames, verifies the result, and only then lets
   the caller replace the original body.
8. **Invalid IR is inspectable.** Builders allow temporarily incomplete graphs inside a mutation
   transaction. Validators return all structured diagnostics; pipeline boundaries require the
   strict profile and fail without partially publishing output.
9. **Determinism is a feature.** IDs, iteration order, textual form, serialization, pass order,
   worklists, and diagnostics are stable for the same input. Obfuscation randomness is supplied
   explicitly through pass context and never hidden in the IR.
10. **Hostile bytecode is data.** Unreachable instructions, overlapping handlers, unusual debug
    ranges, legacy JSR/RET, malformed-but-readable attributes, and opaque custom attributes are
    preserved or diagnosed. They are not silently normalized during import.

## Layering

```
dev.frost.ir.core       ownership, IDs, metadata, context, diagnostics
dev.frost.ir.type       JVM verifier and semantic type lattice
dev.frost.ir.model      mutable SSA/CFG, operations, effects, exceptions
dev.frost.ir.snapshot   immutable persistent method snapshots
dev.frost.ir.analysis   dominance, post-dominance, loops, liveness, SCC, data flow
dev.frost.ir.pass       analysis cache, pass contracts, pipelines, instrumentation
dev.frost.ir.verify     structural, SSA, type, CFG, exception, lowering validators
dev.frost.ir.bytecode   lossless ASM import/export, frames, stack/local SSA construction
dev.frost.ir.analysis   graph/value facts, memory locations, MemorySSA, alias and escape analysis
dev.frost.ir.text       deterministic text, JSON, DOT, and diagnostic dumps
dev.frost.ir.serialization versioned, checksummed persistent snapshot codecs
dev.frost.ir.plugin     versioned dialect/pass/analysis extension contracts
```

`core`, `type`, and `model` are dependency-minimal. Higher layers may depend only downward.
`bytecode` is the only layer allowed to expose ASM types in public signatures.

## Core method shape

An `IrMethod` contains an ordered set of basic blocks and first-class normal/exceptional edges.
Each block owns zero or more phis followed by instructions and exactly one terminator in valid
executable IR. Method parameters are SSA values owned by the entry block. A non-void instruction
defines one typed result; the model permits multiple results for dialect and memory operations.

Every instruction has an `OperationCode`, immutable attributes, operands, result types, an effect
summary, and optional nested regions. Phase 0 starts with flat JVM method CFG regions but reserves
the region ownership model for virtualization, structured reconstruction, and native lowering.

## Type domains

Frost-IR separates three related questions:

- `JvmType`: semantic Java/JVM types used by operations and signatures.
- `VerifierType`: stack-map states, including `TOP`, `NULL`, uninitialized-this, and allocation-site
  uninitialized values.
- analysis facts: nullness, integer ranges, exact runtime classes, constants, taint, and symbolic
  expressions. Facts refine values without mutating their declared types.

This prevents optimization facts from accidentally changing verifier legality.

## Exceptional control flow and memory

Potentially throwing instructions have ordered exceptional successors derived from protected
ranges and JVM handler priority. A catch block receives the thrown object as a dedicated phi-like
entry value. Finally handlers remain catch-all regions until lowering, avoiding source-language
assumptions that do not exist in class files.

Heap state is analyzed with MemorySSA layered over the value SSA graph. Memory locations are
partitioned into fields, statics, arrays, monitors, unknown calls, and volatile/atomic state.
Memory definitions, uses, and phis are analysis objects rather than fake JVM instructions. This
keeps lowering exact while enabling alias-aware propagation and dead-store analysis.

## Pass and analysis contract

Analyses are pure functions of a method revision and declared prerequisites. `AnalysisManager`
caches results by method identity, revision, analysis key, and options. A pass returns a
`PassResult` containing whether it changed the IR, preserved analysis keys, diagnostics, and
metrics. Pipelines validate at configurable boundaries and provide deterministic before/after
listeners. Plugin passes receive capability-scoped contexts rather than engine singletons.

## Phase 1 production optimizers

The first production migrations exercise three distinct invariants:

- `DeadCodeEliminationPass` is a true SSA mark/sweep collector. Observable instructions and
  terminators are roots; liveness walks definitions through stable operand uses; the sweep erases
  a closed definition set atomically, including cross-block chains and cyclic dead phis.
- `CopyPropagationPass` rewrites exact-type copies and trivial phis through
  `Value.replaceAllUsesWith`, preserving declared verifier types and structural def-use symmetry.
- `IrGraphInliner` clones a callee CFG into a caller, substitutes parameters with call operands,
  rebuilds edge-keyed phis, rewrites returns to a continuation block, and merges multiple return
  values with a new phi. Same-class private-static policy avoids class-initialization changes;
  source-bound constants, bootstrap payloads, uninitialized allocations, and exception regions
  currently take the explicit safe fallback.

Lowering materializes parallel copies for all phis, including synthesized phis that have no ASM
frame-slot provenance. This is the shared contract future optimization and obfuscation passes use
when creating SSA control-flow joins.

## Phase capability gates

The project tracks capability by verified gates, not class names:

- **P0-A, model:** ownership, use-def, phi/edge consistency, mutation revisions, snapshots.
- **P0-B, graph:** exact normal/exception CFG, reachability, dominance/post-dominance, loops, SCC.
- **P0-C, lift:** frame simulation and stack-to-SSA import for every class-file opcode, with JSR/RET
  policy and bidirectional ASM mapping.
- **P0-D, lower:** phi destruction, local allocation, stack scheduling, exception table and debug
  restoration, stack-map reconstruction, ASM/JVM verification, transactional replacement.
- **P0-E, analyses:** liveness, SCCP, GVN, symbolic expressions, MemorySSA, alias, escape, ranges.
- **P0-F, platform:** immutable snapshots, deterministic text/JSON/binary serialization, DOT,
  plugin SPI, pass pipelines, tracing, fuzz/property/differential tests.
- **P0-G, migration:** representative Frostfuscator transformations consume Frost-IR and legacy
  duplicated CFG code is removed only after equivalence and regression gates pass.

No adapter may claim lossless round-tripping unless semantic class execution, verifier output,
exception tables, annotations/type annotations, debug scopes, bootstrap constants, unknown
attributes, and stable source mappings are covered by tests.

## Influences and deliberate differences

- LLVM contributes strict SSA, explicit terminators, dominance-based legality, a textual form, and
  separation of value SSA from MemorySSA.
- MLIR contributes namespaced operations, schemas/interfaces, nested regions, immutable attributes,
  and explicit ownership. Frost-IR retains visible phi objects because edge-addressable phis are
  useful to bytecode transformations and diagnostics.
- Graal and C2 contribute sea-of-nodes-inspired value graphs, effect/control distinctions, frame
  state, deoptimization/source positions, graph validation, and aggressive canonicalization.
  Frost-IR keeps scheduled basic blocks as the canonical mutable form because exact class-file
  round trips and adversarial CFG mutation are primary requirements.
- Soot/Jimple/Shimple contribute a flat stackless Java view, explicit monitor/catch operations,
  analysis-friendly locals, and immutable public views. Frost-IR uses real SSA definitions and
  first-class exceptional edges rather than treating SSA as an optional derived body.
- MapleIR demonstrates the value of expression-oriented SSA for Java obfuscation. Frost-IR avoids
  embedding mutable expression trees inside statements: shared values and explicit uses make
  rewriting, ownership, and complexity bounds auditable.

The design is original to Frostfuscator: it optimizes simultaneously for hostile bytecode,
verification-safe emission, obfuscation transformations, deterministic analysis, and long-lived
plugin compatibility.

## Primary references

- [LLVM Language Reference](https://llvm.org/docs/LangRef.html) and
  [MemorySSA](https://llvm.org/docs/MemorySSA.html)
- [MLIR Language Reference](https://mlir.llvm.org/docs/LangRef/) and
  [operation interfaces](https://mlir.llvm.org/docs/Interfaces/)
- [Graal compiler source and Ideal Graph Visualizer](https://github.com/oracle/graal)
- [OpenJDK C2 optimizer source](https://github.com/openjdk/jdk/tree/master/src/hotspot/share/opto)
- [SootUp/Jimple documentation](https://soot-oss.github.io/SootUp/latest/)
- [Soot Shimple optimization documentation](https://soot-oss.github.io/soot/docs/4.4.1/options/soot_options.html)
