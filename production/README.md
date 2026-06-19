# Frostfuscator

Frostfuscator is a Java bytecode obfuscation toolkit with extra release-protection features.

## CLI

```bash
java -jar Frostfuscator.jar [options]
```

Common options:

```text
  -c, --config=<configPath> YAML config file
  -i, --input=<input>       Input JAR
  -o, --output=<output>     Output JAR
  -l, --libs=<libs>         Folder containing dependency JARs
  -t, --transforms=<list>   Comma-separated pass names
      --list-transforms     List passes and exit
```

The old `--transformers` and `--list-transformers` names still work.

## GUI

```bash
java -jar Frostfuscator-gui.jar
```

## Configuration

Use `config.yml` to choose paths, filters, mappings, and passes.
