# Frostfuscator Docs

Frostfuscator is centered on Java bytecode obfuscation, with extra protection, resource, optimization, reporting, and plugin features around it.

## Pages

- [Configuration](configuration.md)
- [Passes](transformers.md)
- [CLI](cli.md)
- [GUI](gui.md)

## How A Run Works

1. The input JAR is loaded.
2. Enabled passes run against classes and resources.
3. Renaming mappings are applied when renaming passes are enabled.
4. Post-remap passes, such as reports, run.
5. Frostfuscator writes the output JAR, optional mapping file, and optional report.

The GUI defaults to **No Passes** so a new project starts from a safe baseline. Obfuscation presets can then be enabled as needed.

## Plugin API

Custom passes extend `Transformer` and are discovered with Java `ServiceLoader`:

```java
public class MyTransformer extends Transformer {
    @Override
    public String getName() {
        return "my-transform";
    }

    @Override
    public void transform(Context context) {
        // inspect or change context.pool(), context.resources(), etc.
    }
}
```

Provider file:

```text
META-INF/services/dev.frost.obfuscator.transformer.Transformer
```

Put the full class name in that file and run Frostfuscator with the plugin JAR on the classpath.

## Requirements

- Java 21 or newer.
- Enough memory for the input JAR.
