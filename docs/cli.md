# CLI Usage

The CLI is packaged as `Frostfuscator.jar`.

```bash
java -jar Frostfuscator.jar [options]
```

## Options

| Option | Long option | Description |
|---|---|---|
| `-c=<path>` | `--config=<path>` | YAML config file to load. |
| `-i=<path>` | `--input=<path>` | Input JAR. Overrides the config value. |
| `-o=<path>` | `--output=<path>` | Output JAR. Overrides the config value. |
| `-l=<path>` | `--libs=<path>` | Folder containing dependency JARs for hierarchy checks. |
| `-t=<list>` | `--transforms=<list>` | Comma-separated pass names. Overrides the config pass list. |
| `-t=<list>` | `--transformers=<list>` | Backward-compatible alias for `--transforms`. |
| | `--list-transforms` | Prints pass names and exits. |
| | `--list-transformers` | Backward-compatible alias for `--list-transforms`. |
| `-h` | `--help` | Prints help. |
| `-V` | `--version` | Prints the version. |

## Examples

Run with a config:

```bash
java -jar Frostfuscator.jar -c config.yml
```

Override input and output:

```bash
java -jar Frostfuscator.jar -c base-config.yml -i app.jar -o release/app-protected.jar
```

Run only selected obfuscation passes:

```bash
java -jar Frostfuscator.jar -i app.jar -o out.jar -t class-rename,string-encryption,remove-debug
```
