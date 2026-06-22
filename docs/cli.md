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
| | `--lib=<path>` | Additional library JAR, ZIP, or directory. Repeat or comma-separate values. |
| | `--libs-recursive[=true|false]` | Recursively scan library directories. |
| | `--libs-runtime[=true|false]` | Load Java runtime classes as library stubs. |
| | `--libs-strict[=true|false]` | Fail the run when library loading has errors. |
| `-t=<list>` | `--transforms=<list>` | Comma-separated pass names. Overrides the config pass list. |
| `-t=<list>` | `--transformers=<list>` | Backward-compatible alias for `--transforms`. |
| | `--plugins=<paths>` | Comma-separated plugin directories. `plugins/` is scanned automatically when present. |
| | `--profile=<name>` | Apply `none`, `basic`, `balanced`, `strong`, or `maximum` before other CLI overrides. |
| | `--dictionary=<name>` | Override dictionary: `alphabet`, `unicode`, or `numeric`. |
| | `--package-mode=<mode>` | Override package handling: `keep`, `flatten`, or `remove`. |
| | `--flatten-package=<name>` | Package name used with `--package-mode=flatten`. |
| | `--include=<regex>` | Add global inclusion regex. Repeat or comma-separate values. |
| | `--exclude=<regex>` | Add global exclusion regex. Repeat or comma-separate values. |
| | `--enable=<list>` | Enable transformers. Repeat or comma-separate names. |
| | `--disable=<list>` | Disable transformers. Repeat or comma-separate names. |
| | `--set=<transform.key=value>` | Set a transformer option, for example `string-encryption.mode=heavy`. |
| | `--mapping[=true|false]` | Enable or disable mapping export. |
| | `--mapping-output=<path>` | Mapping output path. |
| | `--report=<format:path>` | Enable statistics report, for example `json:build/frost-report.json` or `html:report.html`. |
| | `--seed=<number>` | Apply a deterministic seed to seed-aware transformer options. |
| | `--dry-run` | Validate and print the run plan without writing output. |
| | `--frostjni[=true|false]` | Enable or disable FrostJNI. |
| | `--jni-mode=<mode>` | FrostJNI mode: `SELECTIVE` or `FULL`. |
| | `--jni-include-package=<list>` | Add FrostJNI package includes. |
| | `--jni-include-class=<list>` | Add FrostJNI class includes. |
| | `--jni-include-method=<list>` | Add FrostJNI method includes. |
| | `--jni-include-annotation=<list>` | Add FrostJNI annotation includes. |
| | `--jni-exclude-package=<list>` | Add FrostJNI package exclusions. |
| | `--jni-exclude-class=<list>` | Add FrostJNI class exclusions. |
| | `--jni-exclude-annotation=<list>` | Add FrostJNI annotation exclusions. |
| | `--jni-compiler=<list>` | Limit FrostJNI compilers to `clang`, `gcc`, and/or `msvc`. |
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

Apply a GUI-style profile, tune a pass, and inspect the plan:

```bash
java -jar Frostfuscator.jar -i app.jar -o out.jar --profile strong --set string-encryption.mode=condy --seed 12345 --dry-run
```

Enable reports and plugin discovery:

```bash
java -jar Frostfuscator.jar -c config.yml --plugins plugins,build/frost-plugins --report html:build/frost-report.html
```

Run with professional library resolution:

```bash
java -jar Frostfuscator.jar -i app.jar -o out.jar --lib libs --lib paper-api.jar --libs-recursive --libs-runtime --libs-strict
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

FrostJNI registers converted methods internally from `JNI_OnLoad`; protected jars no longer include a `native/native-methods.txt` method map.
