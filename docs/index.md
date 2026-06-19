# Frostfuscator Documentation

Welcome to the official documentation for **Frostfuscator**.

## Navigation
- [Configuration Guide](configuration.md)
- [Transformers Reference](transformers.md)
- [CLI Guide](cli.md)
- [GUI Guide](gui.md)

## What is Frostfuscator?

Frostfuscator is an advanced Java Bytecode Obfuscator. It takes your compiled `.jar` files and applies transformations (known as "transformers") to mutate the bytecode. The resulting `.jar` file behaves exactly like the original but is highly resistant to decompilation and reverse engineering.

## Core Concepts

1. **Input & Output**: Frostfuscator reads an input JAR, processes its `.class` files, and writes an obfuscated output JAR. It automatically copies non-class resources (like images or YAML files) intact.
2. **Transformers**: The atomic units of obfuscation. Each transformer is responsible for one specific task, like encrypting strings, renaming classes, or flattening control flow.
3. **Dictionaries**: Used by renaming transformers. You can use alphanumeric names, zero-width unicode characters, or a custom text file.
4. **Configuration**: A YAML file (`config.yml`) that dictates exactly which transformers to run and how they should behave.

## System Requirements
- Java Development Kit (JDK) or Java Runtime Environment (JRE) version 21 or newer.
- Sufficient RAM (we recommend at least 1GB for large JARs).
