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

Enable FrostJNI through the YAML config:

```yaml
frostjni:
  enabled: true
  includePackages:
    - "com.example.security"
  resourceEmbedding: true
```

FrostJNI runs inside the normal obfuscation pipeline. It generates C++, compiles a native library with a detected compiler, strips selected Java method bodies into `native` declarations, injects the loader, embeds the library under `native/{os}/{arch}/`, and writes the final output jar.

Native protection requires Java 21 and a local C++ compiler. On Windows, install either MSYS2 MinGW-w64 with `g++` on `PATH`, or Clang with working Windows C/C++ runtime headers.
