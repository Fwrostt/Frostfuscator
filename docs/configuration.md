# Configuration Guide

Frostfuscator's behavior is driven entirely by a YAML configuration file. This file defines global settings, file paths, and individual transformer configurations.

## Example `config.yml`

```yaml
input: "my-app.jar"
output: "my-app-obfuscated.jar"

# Dictionary to use for renaming (alphabet, numeric, unicode, file:/path/to/dict)
dictionary: "alphabet"

# Package flattening mode: "keep", "flat", or "obf"
packageMode: "obf"
flattenPackage: "a"

# Library files required for correct hierarchy resolution
libs: "libs/"

# Global exclusions and inclusions (Ant-style matching)
exclusions:
  - "dev/frost/api/**"
  - "dev/frost/ui/Main.class"

inclusions:
  - "**"

mapping:
  enabled: true
  output: "mapping.txt"

transformers:
  # Enable and configure specific transformers
  StringEncryptionTransformer:
    enabled: true
  FlowObfuscationTransformer:
    enabled: true
    complexity: 5
  ClassRenameTransformer:
    enabled: true
    keepAnnotations: false
```

## Global Settings

| Setting | Type | Description |
|---|---|---|
| `input` | String | Path to the source JAR. |
| `output` | String | Path to save the obfuscated JAR. |
| `dictionary` | String | Type of naming dictionary. `alphabet` (a,b,c...), `numeric` (_1, _2...), `unicode` (zero-width chars), or `file:path.txt`. |
| `packageMode` | String | Determines package structure renaming. `keep` (maintains hierarchy), `flat` (moves all to one package), `obf` (renames packages to dictionary values). |
| `flattenPackage` | String | If `packageMode` is `flat` or `obf`, specifies the base package name. |
| `libs` | String | Path to a directory or JAR containing dependencies. Required for proper class hierarchy resolution (e.g. knowing if a class implements a specific interface). |
| `exclusions` / `inclusions` | List<String> | Ant-style glob patterns to include or exclude classes from obfuscation entirely. |

## Mapping File

The mapping file generates a `.txt` mapping of original class, method, and field names to their obfuscated names. This is critical for reading crash logs.
- `mapping.enabled`: Set to `true` to generate mappings.
- `mapping.output`: Path to save the mapping file.

## Configuring Transformers

Each transformer can be configured under the `transformers:` block. The key is the exact class name of the transformer. Every transformer supports an `enabled` flag (boolean). Additional properties depend on the transformer's specific implementation.
