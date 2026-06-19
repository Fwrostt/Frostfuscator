# CLI Usage Guide

The Command Line Interface (CLI) allows you to automate Frostfuscator and integrate it seamlessly into your CI/CD pipelines.

## Executing the CLI

The main CLI application is packaged in `Frostfuscator.jar`. 

```bash
java -jar Frostfuscator.jar [options]
```

## Available Options

| Option | Long Option | Description |
|---|---|---|
| `-c=<path>` | `--config=<path>` | The path to your YAML configuration file (`config.yml`). |
| `-i=<path>` | `--input=<path>` | The path to the JAR file you wish to obfuscate. |
| `-o=<path>` | `--output=<path>` | The destination path for the obfuscated JAR. |
| `-l=<path>` | `--libs=<path>` | A directory containing dependency JAR files required for hierarchy resolution. |
| `-t=<list>` | `--transformers=<list>` | A comma-separated list of transformer names to enable. This overrides the configuration file. |
| | `--list-transformers` | Prints out every available transformer name in the engine and exits. |
| `-h` | `--help` | Displays the help menu. |
| `-V` | `--version` | Displays the current version of Frostfuscator. |

## Examples

**Basic Obfuscation via Config File:**
```bash
java -jar Frostfuscator.jar -c config.yml
```

**Overriding Config File Paths:**
```bash
java -jar Frostfuscator.jar -c base-config.yml -i my-game-v1.jar -o release/my-game-obf.jar
```

**CLI-Only Obfuscation (No Config):**
```bash
java -jar Frostfuscator.jar -i src.jar -o out.jar -t StringEncryptionTransformer,FlowObfuscationTransformer
```
