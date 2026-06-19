# Passes

Frostfuscator organizes work into passes. Obfuscation is the main group; the other groups support release protection and packaging.

## Obfuscation

- **`class-rename`**, **`field-rename`**, **`method-rename`**, **`local-variable-rename`** rename symbols.
- **`string-encryption`**, **`number-obfuscation`**, **`parameter-encryption`** protect constants and supported arguments.
- **`flow-obfuscation`**, **`flow-exception`**, **`flow-outliner`**, **`flow-condition`**, **`flow-range`**, **`flow-switch`**, **`stack-manipulation`** change control flow and bytecode layout.
- **`invoke-dynamic`**, **`reference-hiding`** change call/reference structure.
- **`remove-debug`**, **`access-modifier`**, **`metadata-noise`** adjust debug and metadata output.

## Protection

- **`watermark`** embeds owner/build identifiers into class metadata and writes `META-INF/frostfuscator/watermark.properties`.
- **`integrity`** writes a SHA-256 index for classes and resources.
- **`anti-debug`** injects case-insensitive JDWP checks into application methods.
- **`anti-decompiler`** adds verifier-safe bytecode traps aimed at CFR, FernFlower, Procyon, and JADX output.

## Resources

- **`resource-compression`** stores compressed resource copies and writes an index. Frostfuscator metadata is skipped so generated ownership/integrity files are not recursively protected.

## Optimization

- **`bytecode-optimizer`** removes simple `NOP` instructions.
- **`jar-shrinker`** removes debug tables, line numbers, and source metadata.

## Reporting

- **`statistics-report`** writes JSON or HTML metrics for classes, methods, resources, mappings, and pass counters.

## Plugins

Plugin passes extend `Transformer` and override `transform(Context context)`. Frostfuscator discovers them with `ServiceLoader`.
