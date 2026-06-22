# Plugins

Frostfuscator plugins are extension jars discovered from `plugins/`, config `plugins:`, or the CLI `--plugins` option.

## Discovery

Frostfuscator scans each plugin directory for `.jar` files. A jar is loaded when it has either:

- `frost-plugin.yml` or `frost-plugin.yaml`
- `META-INF/services/dev.frost.obfuscator.transformer.Transformer`

The built-in Java `ServiceLoader` path is still supported for simple transformer-only plugins.

## Descriptor

```yaml
name: ExampleProtection
version: 1.0.0
main: com.example.frost.ExamplePlugin
description: Extra Frostfuscator passes
authors:
  - Example Studios
transformers:
  - example-protection
```

`main` is optional. When present, the class must implement `dev.frost.obfuscator.plugin.FrostPlugin`.

## Entrypoint

```java
package com.example.frost;

import dev.frost.obfuscator.plugin.FrostPlugin;
import dev.frost.obfuscator.plugin.PluginContext;

public final class ExamplePlugin implements FrostPlugin {
    @Override
    public void onLoad(PluginContext context) {
        context.registerTransformer(new ExampleProtectionTransformer());
    }
}
```

## Transformer

```java
package com.example.frost;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;

public final class ExampleProtectionTransformer extends Transformer {
    @Override
    public String getName() {
        return "example-protection";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public void transform(Context context) {
        // Use context.pool(), context.jar(), context.mappings(), and context.stats().
    }
}
```

## ServiceLoader

For transformer-only plugins, add:

```text
META-INF/services/dev.frost.obfuscator.transformer.Transformer
```

with one transformer implementation class name per line.

## Running

```bash
java -jar Frostfuscator.jar -i app.jar -o app-protected.jar --plugins plugins --enable example-protection
```

Unknown enabled transformers fail validation, so missing or misspelled plugin passes are caught before jar processing starts.
