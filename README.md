# Frostfuscator ❄️

Welcome to **Frostfuscator**, a professional-grade, highly customizable Java bytecode obfuscator designed to secure your intellectual property from reverse engineering. Built on top of the powerful ASM framework, Frostfuscator applies state-of-the-art obfuscation techniques without compromising your application's logic or performance.

---

## 📖 Documentation

We have prepared comprehensive documentation for every aspect of Frostfuscator. Please refer to the `/docs` folder for detailed guides:

- [**Overview & Getting Started**](docs/index.md)
- [**CLI Usage Guide**](docs/cli.md)
- [**GUI Usage Guide**](docs/gui.md)
- [**Configuration Reference**](docs/configuration.md)
- [**Transformers Deep Dive**](docs/transformers.md)

---

## ✨ Features

Frostfuscator is equipped with a vast array of transformers tailored for advanced code protection. 

### 🧹 Cleanup Transformers
- **Debug Removal:** Strips out `LocalVariableTable`, `LineNumberTable`, and source file attributes, making the code extremely hard to read in decompilers.
- **Access Modifier Modification:** Scrambles visibility flags (e.g., changing private fields to public or vice versa where safe) to confuse reverse engineers.
- **Metadata Noise:** Injects junk annotations and attributes that break automated analysis tools.

### 🔒 Encryption Transformers
- **String Encryption:** Encrypts string literals in the bytecode, decrypting them at runtime dynamically.
- **Number Obfuscation:** Replaces static numeric constants with complex arithmetic expressions.
- **Parameter Encryption:** Obfuscates method signatures and parameters to obscure control data.

### 🔀 Control Flow Obfuscation
- **Flow Flattening:** Transforms linear code execution paths into massive `switch` blocks within a loop, destroying the logical structure.
- **Exception Flow:** Uses try-catch blocks to dictate program flow instead of standard branch instructions.
- **Outlining:** Extracts parts of methods into synthetic helper methods, breaking up cohesive logic.
- **Stack Manipulation:** Pushes and pops junk data onto the JVM stack to confuse stack-based decompilers.

### 🪞 Indirection
- **Reference Hiding:** Replaces direct field and method accesses with synthetic proxy getters/setters or reflection.
- **InvokeDynamic:** Converts static method invocations into dynamic call sites (`invokedynamic`), making static analysis practically impossible.

### 🏷️ Renaming
- **Class, Method, and Field Renaming:** Replaces descriptive names with meaningless characters (e.g., `a`, `b`, `c`).
- **Local Variable Renaming:** Destroys local variable semantics.
- **Custom Dictionaries:** Choose from Alphabet, Numeric, Unicode, or provide your own text file dictionary.

---

## 🚀 Quick Start

### 1. Prerequisites
- **Java 21** or higher.

### 2. Download
Grab the latest `Frostfuscator.jar` and `Frostfuscator-gui.jar` from the releases.

### 3. Run (CLI)
Obfuscate your JAR file instantly using the command line:
```bash
java -jar Frostfuscator.jar -i input.jar -o output-obfuscated.jar -c config.yml
```

### 4. Run (GUI)
Prefer a visual approach? Launch the graphical interface:
```bash
java -jar Frostfuscator-gui.jar
```

---

## ⚙️ Building from Source

To build Frostfuscator yourself:
```bash
# Clean the workspace and build the JARs
./gradlew clean shadowJar guiShadowJar

# Artifacts will be available in build/libs/
```

## 📜 License
This project is proprietary software. All rights reserved.
