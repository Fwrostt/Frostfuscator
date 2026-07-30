# Graph analysis

Frostfuscator has a renderer-independent graph system for inspecting application bytecode and the obfuscation pipeline. The `frost-graph` module contains immutable models, an indexed ASM analysis backend, scoped graph projection, limits, caching, and text exporters. It has no JavaFX or browser dependency.

## Available analyses

Bytecode graphs:

- **dependencies** — class references collected from inheritance metadata, descriptors, signatures, annotations, declared exceptions, bytecode instructions, method handles, bootstrap arguments, `invokedynamic`, and `ConstantDynamic`;
- **calls** — calls entering and leaving a selected class or method, including dynamic invocation bootstrap ownership;
- **inheritance** — superclass, interface, implementation, and subclass relationships around a selected class;
- **packages** — aggregated cross-package dependencies with reference counts;
- **cfg** — one method's basic blocks, branches, fallthrough, switches, returns, throws, try/catch flow, loop backedges, reachability, stack-frame sizes, and block instructions.

Obfuscation and execution graphs:

- class-scoped obfuscation dry runs with inclusion/exclusion decisions and applicable transformers;
- transformer pipeline order and dependency/conflict validation;
- mappings and generated members;
- transformation before/after diffs;
- actual per-transformer results and complete build/verification execution.

Transformer result metadata includes the stable id, display name, enabled state, phase, priority, dependencies, conflicts, inspected/modified/generated counts, elapsed time, warnings, failure summary, and counter deltas.

## Desktop graph workbench

Open **Graphs** in the sidebar and choose an analysis. Detailed relationship graphs require a class by default, so a large archive is never sent to the renderer accidentally. Class and method selectors search a reusable project index; selecting an overload preserves its exact JVM descriptor. Archive-wide detail graphs remain available as an explicit opt-in under **Limits**.

Use **Obfuscation dry run** to select one class and see which enabled transformers would inspect it under the current global and transformer-level inclusion/exclusion rules. This mode does not mutate bytecode. **Latest build execution** uses real measurements from the most recently completed run and refreshes automatically when a new build snapshot arrives.

The viewer supports:

- node search and semantic node/edge filters;
- library and isolated-node filtering;
- incoming, outgoing, or bidirectional traversal with a configurable depth;
- automatic, directional, force, radial, and grid layouts;
- left-to-right or top-to-bottom flow direction;
- pan, zoom, fit, node selection, neighborhood highlighting, metadata, and connected-edge inspection;
- opening selected classes or methods in the bytecode page;
- comparison summaries between consecutive results of the same graph type;
- JSON clipboard copy and Mermaid, JSON, DOT, or full-canvas PNG export.

Expensive loading and analysis run on a cancellable, minimum-priority background daemon. Class, method, dependency, and call metadata are indexed once per loaded project and reused by every graph builder. Archive bytes, the index, graph cache, worker, Cytoscape document, and WebView are released when the page is hidden.

Cytoscape.js 3.33.4 is bundled in the packaged GUI. Its canvas renderer avoids Mermaid's diagram text-size ceiling and remains responsive for substantially larger focused graphs. Rendering uses an isolated local document with a restrictive content-security policy, no network permission, and no Java object bridge. Mermaid remains a headless export format.

## CLI

```bash
java -jar Frostfuscator.jar graph \
  -i application.jar \
  --type dependencies \
  --focus com/example/Main \
  --direction BOTH \
  --depth 2 \
  --format json \
  -o build/main-dependencies.json
```

Supported CLI types are `dependencies`, `calls`, `inheritance`, `packages`, `cfg`, `pipeline`, `transformers`, `mappings`, and `build`. Text formats are `json`, `mermaid`, and `dot`; the CLI remains headless and never loads the GUI renderer.

Control-flow example:

```bash
java -jar Frostfuscator.jar graph -i application.jar --type cfg \
  --class com.example.Main --method run --descriptor "()V" \
  --format mermaid -o build/main-run.mmd
```

Use `--include-libraries`, repeated or comma-separated `--lib`, `--max-nodes`, `--max-edges`, `--focus`, `--depth`, and `--direction INCOMING|OUTGOING|BOTH` to control large projects. Focus projection happens before safety limits, so a selected class cannot be discarded because it appears late in a large archive. Limit hits produce a usable truncated graph plus structured warnings.

An ordinary protection run can export both stages:

```bash
java -jar Frostfuscator.jar -c config.yml \
  --graph-pipeline build/pipeline.json \
  --graph-build build/completed-build.json \
  --graph-format json
```

The first file is written before bytecode transformation. The second uses actual measurements captured during the run.

## Performance and cache behavior

Default safety limits are 600 nodes, 1,800 edges, and depth 2. IDs and output ordering are deterministic, and duplicate semantic edges collapse. Whole-project cache keys include a SHA-256 fingerprint and graph options. CFG keys include class, method, descriptor, bytecode hash, and options. Cache entries use soft references and can be cleared explicitly.

JAR inputs retain only class bytes plus an immutable summary index. ASM visitors stream analysis, and CFG parsing retains a `MethodNode` only for the duration of that method build. No `ClassNode` or `MethodNode` is stored in the neutral graph or cache.

## Embedding

Use `GraphService` from `frost-core` for the shared headless facade, or use builders directly from `frost-graph`. Exporters implement `GraphExporter`; Mermaid syntax is emitted only by `MermaidGraphExporter`.

Plugins can register custom builders, metadata providers, filters, context actions, and exporters through the public `PluginContext`. These contracts expose neutral graph records and sanitized plugin context only—never the embedded renderer.
