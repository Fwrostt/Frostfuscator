# Frost-IR

Frost-IR is Frostfuscator's typed SSA compiler infrastructure. It provides an ownership-safe
mutable IR, immutable snapshots, first-class normal and exceptional CFG edges, edge-keyed phi
nodes, typed values and use-def chains, graph/data-flow analyses, validation, pass pipelines,
debug formats, and an ASM boundary.

See the [architecture and capability gates](../docs/frost-ir.md).

## Current Phase 0 gates

| Gate | Status |
| --- | --- |
| P0-A model | Implemented and unit tested |
| P0-B graph | Dominance, post-dominance, frontiers, SCC, loops, reachability implemented |
| P0-C lift | Typed stack/local SSA, edge phis, frames, all modern opcodes, indy/condy and legacy JSR/RET normalization implemented |
| P0-D lower | SSA local allocation, phi destruction, exception reconstruction, stack-map rebuilding, debug scopes and code/type-annotation reconstruction, verification and executable mutation tests implemented |
| P0-E analyses | Liveness, SCCP, GVN/symbolic expressions, nullness/ranges, MemorySSA, alias, escape and effect-safe DCE implemented |
| P0-F platform | Pass cache/pipeline, immutable snapshots, checksummed deterministic JSON and binary codecs, text/DOT, transactional plugin SPI and differential fuzzing implemented |
| P0-G migration | Production optimization, flow-range, parameter encryption, typed reference/reflection hiding, indy/condy indirection, method salting, and string reconstruction run transactionally over Frost-IR |

## Phase 1 optimizer migrations

| Transformer | Frost-IR implementation |
| --- | --- |
| `dead-code-elimination` | Strict SSA import, unreachable-CFG cleanup, use-def mark/sweep DCE (including dead phi cycles), verified lowering, then conservative private-member reachability cleanup |
| `bytecode-optimizer` | SCCP constant folding, copy/trivial-phi propagation, unreachable cleanup, GVN/CSE, mark/sweep DCE, critical-edge normalization, and verified lowering |
| `aggressive-inlining` | Bounded same-class private-static inter-method SSA graph cloning with argument substitution, CFG/phi reconstruction, multi-return merging, validation, verified lowering, and safe source-bound/EH fallbacks |

Every adapter publishes a fresh ASM method only after lowering succeeds. Unsupported or unverifiable
methods remain owned by the legacy boundary rather than receiving a partial rewrite.

## Obfuscation migrations

| Transformer family | Frost-IR contract |
| --- | --- |
| `flow-range` | Creates ordered exception regions, exceptional edges, edge exception values, and a typed rethrow handler |
| `parameter-encryption` | Rewrites exact integral parameter entry uses and all private-static direct callsite operands as one staged interprocedural transaction |
| `reference-hiding`, `reflection-hiding` | Selects typed member operations and substitutes proxy invokes or encrypted dynamic callsites without stack-pattern editing |
| `invoke-dynamic`, `condy-indirection` | Owns lossless bootstrap handles/arguments (including nested condy values) as serializable IR attributes and emits new dynamic operations without source-node dependence |
| `method-salting` | Inserts stack-independent SSA copies and explicit IR no-ops |
| `string-splitting` | Reconstructs literals with typed SSA fragment-accessor and concat invokes; generated carrier/accessor methods remain at the ASM boundary |

The importer exposes capability flags. Consumers must check these rather than infer support from
the presence of an IR method. In particular, CFG-only imports contain opaque operations and cannot
be lowered after mutation.

## Verification

Run the module suite with Java 21:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat :frost-ir:test
```
