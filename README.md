# Frostfuscator

Frostfuscator is a Java bytecode obfuscator built with ASM. Originally made for protecting Minecraft plugins and mods, it works with any Java application.

The main focus is obfuscation, with support for class and member renaming, string encryption, control-flow transformations, `invokedynamic`, JNI native protection, and a standalone Plugin API (`frost-api`).

## Documentation

* [Getting Started](docs/index.md)
* [CLI Usage](docs/cli.md)
* [GUI Usage](docs/gui.md)
* [Configuration](docs/configuration.md)
* [Transformers](docs/transformers.md)
* [Plugins](docs/plugins.md)
* [Graph Visualization](docs/graphs.md)

## Features

### Obfuscation & Protection
* Rename classes, methods, fields, local variables, and parameters.
* Encrypt strings (with inline anti-tamper stack checks) and mutate numeric constants.
* Control-flow transformations, opaque predicates, switch rewriting, basic-block shuffling, exception-based flow, outlining, and polymorphic instruction substitution.
* ConstantDynamic (Condy) indirection & invokedynamic proxies for method and field reference hiding.
* Anti-debug, anti-agent (javaagent/ByteBuddy detector), and decompiler parser crashers (Jadx, CFR, Procyon, Fernflower).
* Convert selected Java methods into native JNI stubs through FrostJNI.
* Junk members, class/method salting, line number spoofing, and metadata noise.

### Plugin API & Extensions (`frost-api`)
* Standalone `frost-api` module with priority-aware `EventBus` (`PreObfuscationEvent`, `ClassTransformEvent`, `PostObfuscationEvent`).
* Extension interfaces for custom obfuscation transformers (`PluginTransformer`), string encryptors (`StringEncryptorPlugin`), symbol name generators (`NameGeneratorPlugin`), decompiler backends (`CustomDecompilerProvider`), and GUI extensions (`UiExtensionPoint`).
* In-decompiler IDE editing mode with in-memory Java compiler (`InJarJavaCompiler`), ASM bytecode assembler (`BytecodeAssembler`), and staged workspace.

### Graph Analysis
* Renderer-neutral class dependency, method-call, inheritance, package, and per-method control-flow graphs.
* Transformer pipeline validation, mapping/diff/generated-member views, and actual completed-build execution statistics.
* Scalable, class-scoped Cytoscape desktop analysis plus deterministic Mermaid, JSON, and DOT headless exports.

### Resources & Containers
* Fabric Mod (`fabric.mod.json`) parsing and Mixin remapping.
* Built-in framework exclusion presets (`spigot`, `fabric`, `forge`, `gson`, `jackson`, `spring`, `jpa`, `sponge`).
* Nested Fat JAR support (`BOOT-INF/lib/*.jar`, `WEB-INF/lib/*.jar`, `META-INF/jars/*.jar`).

## Quick Start

### Requirements
* Java 21 or newer
* Optional for FrostJNI: Clang or GCC/MinGW native C++ toolchain

### CLI

```bash
java -jar Frostfuscator.jar -i input.jar -o output-protected.jar -c config.yml
```

List available transformers:

```bash
java -jar Frostfuscator.jar --list-transforms
```

Generate a dependency graph without loading the GUI:

```bash
java -jar Frostfuscator.jar graph -i input.jar --type dependencies --format json -o dependencies.json
```

### GUI

```bash
./gradlew runGui
```

Or run the packaged application directly:

```bash
java -jar frost-gui/build/libs/Frostfuscator-gui.jar
```

## Building

```bash
./gradlew clean build
```

The runnable CLI JAR is written to `frost-cli/build/libs/Frostfuscator.jar`. The runnable GUI JAR and launchers are written to `frost-gui/build/libs/`.

## License

This project is proprietary software. All rights reserved.
