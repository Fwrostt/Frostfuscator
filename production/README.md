# Frostfuscator

Frostfuscator is a professional-grade Java bytecode obfuscator designed to protect your Java applications from reverse engineering. Built using the ASM framework, it applies powerful obfuscation techniques to your compiled classes.

## Features

Frostfuscator includes a comprehensive suite of transformers that mutate your Java bytecode in various ways, ensuring robust protection without breaking execution:

- **Cleanup Transformers**
  - Removes debug information to prevent trivial reverse engineering.
  - Modifies access modifiers.
  - Injects metadata noise.

- **Encryption Transformers**
  - String Encryption.
  - Number Obfuscation.
  - Parameter Encryption.

- **Control Flow Obfuscation**
  - Advanced flow obfuscation and outliner.
  - Control flow flattening.
  - Stack manipulation and switch obfuscation.

- **Indirection**
  - Reference hiding via proxies.
  - InvokeDynamic transformations.

- **Renaming**
  - Obfuscates classes, methods, fields, and local variables.

## Getting Started

### Prerequisites
- Java 21+

### Usage (CLI)

Run the obfuscator from the command line using the compiled JAR:

```bash
java -jar Frostfuscator.jar [options]
```

**Options:**
```
  -c, --config=<configPath> Path to config.yml file
  -h, --help                Show this help message and exit.
  -i, --input=<input>       Path to input JAR file
  -l, --libs=<libs>         Path to a folder containing library JARs for hierarchy resolution
      --list-transformers   List all available transformers and exit
  -o, --output=<output>     Path to output JAR file
  -t, --transformers=<list> Comma-separated list of transformers to enable (overrides config)
  -V, --version             Print version information and exit.
```

### Usage (GUI)

Frostfuscator also features a desktop GUI built with JavaFX for an intuitive configuration experience:

```bash
java -jar Frostfuscator-gui.jar
```

## Configuration

You can use a `config.yml` file to finely control which transformers are applied and how they behave. Pass the config file using the `-c` or `--config` argument in the CLI.

---
*Built with Gradle and ASM.*
