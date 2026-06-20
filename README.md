# Frostfuscator

Frostfuscator is a Java bytecode obfuscator built with ASM. I originally made it for protecting Minecraft plugins and mods, but it works with any Java application.

The main focus is obfuscation, with support for class and member renaming, string encryption, control-flow transformations, `invokedynamic`, and other techniques to make reverse engineering harder.

Alongside the obfuscation passes, Frostfuscator includes some extra tools for shrinking, reporting, resource handling, and custom plugins.

## Documentation

* [Getting Started](docs/index.md)
* [CLI Usage](docs/cli.md)
* [GUI Usage](docs/gui.md)
* [Configuration](docs/configuration.md)
* [Transformers](docs/transformers.md)

## Features

### Obfuscation

* Rename classes, methods, fields, local variables, and parameters.
* Encrypt strings and mutate numeric constants.
* Apply control-flow transformations, opaque predicates, switch rewriting, exception-based flow, outlining, and stack noise.
* Hide method calls through proxies or `invokedynamic`.
* Remove debug information and add metadata noise.

### Protection

* Add watermarks for ownership or build identification.
* Generate SHA-256 integrity metadata.
* Optional anti-debug checks.
* Add decompiler-unfriendly but verifier-safe patterns.
* Add junk members and decoy classes to increase static-analysis noise.
* Optional Funsies passes for custom banners and full Chinese-name chaos mode.

### Resources And Output

* Compress resources.
* Store encrypted resource copies for applications with a matching resource loader.
* Strip debug tables and source information.
* Generate JSON or HTML reports.
* Support custom transformers through Java `ServiceLoader`.

## Quick Start

### Requirements

* Java 17 or newer

### CLI

```bash
java -jar Frostfuscator.jar -i input.jar -o output-protected.jar -c config.yml
```

List available transformers:

```bash
java -jar Frostfuscator.jar --list-transforms
```

### GUI

```bash
java -jar build/libs/Frostfuscator-gui.jar
```

On systems with Java 17 or newer installed and `.jar` files associated with Java, the GUI can also be opened by double-clicking `build/libs/Frostfuscator-gui.jar`. The GUI starts with no transformers enabled. You can load a preset or enable passes manually through the different categories.

## Building

```bash
./gradlew clean build
```

The compiled JARs will be placed in `build/libs/`, including the CLI JAR and `Frostfuscator-gui.jar`.

## License

This project is proprietary software. All rights reserved.
