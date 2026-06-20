# Repository Guidelines

## Project Structure & Module Organization

Frostfuscator is a Java 21 bytecode obfuscator built with Gradle, ASM, picocli, SnakeYAML, and JavaFX. Main source code lives in `src/main/java/dev/frost/obfuscator`. Key packages are `engine` for JAR processing, `transformer` for obfuscation/protection passes, `config` for YAML loading, `dictionary` for renaming strategies, `remapper` for mapping support, and `gui` for the desktop app. Runtime resources are in `src/main/resources`, including `config.yml` and `frost-gui.css`. Documentation lives in `docs/`; update it when CLI flags, config keys, or transformer behavior changes. Tests belong in `src/test/java` and `src/test/resources`.

## Build, Test, and Development Commands

Use the Gradle wrapper. On Windows PowerShell, replace `./gradlew` with `.\gradlew.bat`.

- `./gradlew clean build`: compile, test, and create the default CLI ShadowJar.
- `./gradlew shadowJar`: build the runnable CLI JAR in `build/libs/`.
- `./gradlew guiShadowJar`: build the JavaFX GUI JAR with runtime dependencies.
- `./gradlew run --args="--list-transforms"`: run the CLI from source.
- `./gradlew run --args="-c src/main/resources/config.yml"`: run with a YAML config.
- `./gradlew runGui`: launch the desktop GUI.
- `./gradlew test`: run the JUnit Platform test task.

## Coding Style & Naming Conventions

Use 4-space indentation, UTF-8, and Java 21 APIs. Keep packages lowercase under `dev.frost.obfuscator`. Use `PascalCase` classes, `camelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. Transformer implementations should end in `Transformer`, return a stable config name from `getName()` such as `string-encryption`, and live in the matching category package. Register built-in passes in `TransformerRegistry`; external passes may use `ServiceLoader<Transformer>`. Log through `Logger`, not `System.out`.

## Testing Guidelines

JUnit Platform is configured, though the repository currently has no committed test cases. Add focused tests beside production packages, named like `ConfigLoaderTest` or `StringEncryptionTransformerTest`. Prioritize coverage for YAML parsing, transformer enablement/order, dictionary generation, remapping, and verifier-sensitive bytecode changes. Run `./gradlew test` before submitting changes; for transformer work, also test a small input JAR manually.

## Commit & Pull Request Guidelines

Recent commits are short and imperative, for example `bump frostfuscator 1.1.0`. Keep that style, but be specific: `fix config override validation` is better than `bump fix`. Pull requests should include a summary, test results, related issues, and screenshots or recordings for GUI changes. For obfuscation changes, describe the input scenario, enabled transforms, and compatibility or verifier impact.

## Security & Configuration Tips

This is proprietary software. Do not commit customer JARs, generated protected outputs, private mappings, secrets, or local IDE metadata. Keep sample config generic, preserve reflection/JNI/plugin entry point exclusions, and document new YAML options in `docs/configuration.md`.
