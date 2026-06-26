# Passes

Frostfuscator organizes work into passes. Obfuscation is the main group; the other groups support release protection and packaging.

## Obfuscation

- **`class-rename`**, **`field-rename`**, **`method-rename`**, **`local-variable-rename`** rename symbols.
- **`string-encryption`**, **`number-obfuscation`**, **`parameter-encryption`** protect constants and supported arguments.
- **`flow-obfuscation`**, **`flow-exception`**, **`flow-outliner`**, **`flow-condition`**, **`flow-range`**, **`flow-switch`**, **`stack-manipulation`** change control flow and bytecode layout.
- **`invoke-dynamic`**, **`reference-hiding`** change call/reference structure.
- **`remove-debug`**, **`access-modifier`**, **`metadata-noise`** adjust debug and metadata output.

## License

- **`license-guard`** injects a pre-obfuscation runtime verifier for commercial licensing, trials, HWID binding, signed license tokens, feature claims, and clock rollback checks. Later obfuscation passes can rename and harden the injected runtime.

## Protection

- **`watermark`** embeds owner/build identifiers into class metadata and writes `META-INF/frostfuscator/watermark.properties`.
- **`integrity`** writes a SHA-256 index for classes and resources.
- **`anti-debug`** injects JVM argument, debugger-agent class, stack trace, timing, and optional process checks.
- **`anti-decompiler`** adds verifier-safe bytecode traps aimed at CFR, FernFlower, Procyon, and JADX output.
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

## Resources

- **`resource-compression`** stores compressed resource copies and writes an index. Frostfuscator metadata is skipped so generated ownership/integrity files are not recursively protected.
- **`resource-encryption`** stores XOR-encrypted resource copies and writes an index. Keep originals unless the application has a matching runtime resource loader.

## Optimization

- **`bytecode-optimizer`** removes simple `NOP` instructions.
- **`jar-shrinker`** removes debug tables, line numbers, and source metadata.

## Reporting

- **`statistics-report`** writes JSON or HTML metrics for classes, methods, resources, mappings, and pass counters.

## Plugins

Plugin passes extend `Transformer` and override `transform(Context context)`. Frostfuscator discovers plugin jars from `plugins/`, extra `plugins:` config entries, or `--plugins`.
