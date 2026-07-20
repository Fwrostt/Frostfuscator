# Passes

Frostfuscator organizes work into passes. Obfuscation is the main group; the other groups support release protection and packaging.

## Obfuscation

- **`class-rename`**, **`field-rename`**, **`method-rename`**, **`local-variable-rename`** rename symbols.
- **`string-splitting`** replaces each eligible executable string literal with one relocated call. Unicode-safe fragments, decoys, assembly methods, and optional relay chains are injected into safe pre-existing application classes; the pass creates no recognizable carrier classes. It runs before mapping collection, so injected owners and members participate in class/method renaming and later flow, call-indirection, number, and string-encryption passes. Reflection-sensitive names are preserved by default for rename/remap compatibility.
- **`string-encryption`**, **`number-obfuscation`**, **`parameter-encryption`** protect constants and supported arguments.
- **`mixed-boolean-arithmetic`** replaces `int` and `long` add, subtract, bitwise, XOR, and negation operations with randomly selected equivalent Boolean-arithmetic identities. It preserves Java's modular overflow behavior, supports one to three rounds, uses bounded scratch locals, and enforces per-method, per-class, input-size, and output-size limits. It runs after numeric constant obfuscation so both passes can contribute without rewriting each other's constants.
- **`flow-obfuscation`** provides a layered, budgeted opaque-predicate framework and hybrid adaptive flattening. Eight predicate families use argument/object inputs and unique per-class keys, lookup data, volatile state, and synthetic helpers; optional thread/time/environment reads are algebraically cancelled single-read camouflage. The adaptive CFG planner selects full or partial regions and one of lookup, table, computed, nested, or split dispatchers. Per-transition XOR/add codecs, fake states, safe decoy clones, loop-aware cost controls, ASM frame analysis, and output budgets preserve verifier correctness and bound JIT/code-size impact.
- **`flow-exception`**, **`flow-outliner`**, **`flow-condition`**, **`flow-range`**, **`flow-switch`**, **`stack-manipulation`** provide additional control-flow and bytecode-layout transformations.
- **`reflection-hiding`** converts eligible public `java.io`, `java.net`, NIO file, ZIP, and JAR API calls into `invokedynamic` sites backed by `MethodHandles.publicLookup()`. Target owner, method name, and descriptor are independently encrypted in bootstrap arguments; constructors and non-public targets are deliberately skipped. It runs before the general `invoke-dynamic` and `reference-hiding` passes so each direct call is consumed once.
- **`invoke-dynamic`**, **`reference-hiding`** change the remaining call/reference structure.
- **`remove-debug`**, **`access-modifier`**, **`metadata-noise`** adjust debug and metadata output.

## License

- **`license-guard`** injects a pre-obfuscation runtime verifier for commercial licensing, trials, HWID binding, signed license tokens, feature claims, and clock rollback checks. Later obfuscation passes can rename and harden the injected runtime.

## Protection

- **`watermark`** embeds owner/build identifiers into class metadata and writes `META-INF/frostfuscator/watermark.properties`.
- **`integrity`** writes a SHA-256 index for classes and resources.
- **`anti-debug`** injects JVM argument, debugger-agent class, stack trace, timing, and optional process checks.
- **`anti-attach`** injects entrypoint or all-class startup checks for attach exposure. It can require `-XX:+DisableAttachMechanism`, optionally require `-XX:-EnableDynamicAgentLoading`, reject launch agents, and reject a visible JVM Attach Listener thread. Its helper class and public guard method are randomized and relocated by default.
- **`runtime-self-checksum`** injects class initializer checks that hash the emitted `.class` resource bytes at runtime and compare them with `META-INF/frostfuscator/runtime-checksums.tsv`. This protects normal raw class entries; it is not a JVM-internal memory scanner. Its helper class and public guard method are randomized and relocated by default.
- **`anti-decompiler`** adds verifier-safe bytecode traps aimed at CFR, FernFlower, Procyon, and JADX output.
- **`structural-hardening`** adds JVM-valid opaque class, method, and field attributes with bounded payloads. It intentionally avoids malformed constant pools, invalid lengths, and illegal flags because those are rejected by the JVM before the program can run.
- **`archive-extraction-canary`** adds bounded, highly compressible dummy resources and a metadata index. It is a safe extraction canary, not an unbounded zip bomb.
- **`classloader-encryption`** encrypts eligible application classes into a compressed AES database, removes their raw `.class` entries, and injects a decrypting runtime loader. Standalone jars are launched through `dev.frost.loader.Bootstrap`; Bukkit/Paper plugin jars keep the plugin entry shell loadable and only encrypt plugin-compatible same-package classes that can be defined safely from the plugin main lookup.
- **`virtualization`** translates eligible methods into a randomized VM instruction set, stores encoded VM bytecode in synthetic fields, and injects `FrostVM` to execute the protected methods at runtime. It skips handlers, invokedynamic, synchronized code, oversized methods, and loader classes for verifier and API compatibility.
- **`junk-code`** adds bounded synthetic fields and methods to real classes.
- **`fake-classes`** generates verifier-safe decoy classes. Placement can follow package mode, reuse existing packages, use a specific package, or remove packages; names can follow the active dictionary, a custom pattern, confusable text, or Chinese text.

## Funsies

- **`inject-banner`** injects custom text or ASCII banners into every class.
- **`emoji-hell`** injects emoji noise strings into classes.
- **`copypasta-injector`** injects noisy joke/error strings into classes.
- **`fake-application`** generates inert but believable themed classes for profiles such as Minecraft plugins, Spring Boot, networking, AI, SCP, quantum, and enterprise code.
- **`chinese-mode`** remaps classes, methods, and fields to random Chinese identifiers and injects Chinese banner/noise members after other generated classes exist. Package mode can use one global package, random Chinese package paths, existing packages, or no package.
- **`language-mixup`** remaps eligible classes and methods to legal JVM identifiers resembling C++ mangling, Kotlin synthetic accessors, Scala adapters, or a mixture of all three.
- **`decompiler-zip-ties`** adds bounded, deeply nested recursive generic signatures to synthetic fields and methods, plus a cyclic type-variable class signature trap by default. The pass does not emit cyclic inheritance because the JVM rejects inheritance cycles before application code can run.
- **`troll-stack-traces`** rewrites explicit `Throwable.printStackTrace` calls to print a configured ASCII banner. It leaves the caught exception object and normal exception-table behavior intact.

## Resources

- **`resource-compression`** stores compressed resource copies and writes an index. Frostfuscator metadata is skipped so generated ownership/integrity files are not recursively protected.
- **`resource-encryption`** stores XOR-encrypted resource copies and writes an index. Keep originals unless the application has a matching runtime resource loader.
- **`resource-steganography`** AES-GCM encrypts selected resource extensions, stores the ciphertext in PNG RGB least-significant bits, and injects `dev.frost.runtime.StegoResourceLoader`. When originals are removed, use `StegoResourceLoader.read(name, password)` or `open(name, password)` at runtime.
- **`resource-splitting`** breaks resources above `minimum-size` into `part-size` fragments, records a SHA-256 checksum, and injects `dev.frost.runtime.SplitResourceLoader`. When originals are removed, use `SplitResourceLoader.read(name)` or `open(name)`.

## Optimization

- **`bytecode-optimizer`** removes simple `NOP` instructions.
- **`jar-shrinker`** removes debug tables, line numbers, and source metadata.
- **`aggressive-inlining`** inlines tiny private static no-argument straight-line methods. It rejects branches, handlers, locals, synchronization, native code, and recursion, and can remove methods whose call sites were fully inlined.
- **`dead-code-elimination`** computes method reachability from externally callable roots and removes unreachable private methods. It can also remove unused private fields; disable that option for reflection-heavy applications.

## Reporting

- **`statistics-report`** writes JSON or HTML metrics for classes, methods, resources, mappings, and pass counters.

## Plugins

Plugin passes extend `Transformer` and override `transform(Context context)`. Frostfuscator discovers plugin jars from `plugins/`, extra `plugins:` config entries, or `--plugins`.
