# Configuration

Frostfuscator uses YAML for paths, filters, mappings, and pass settings.

The key is still named `transformers:` for compatibility, but it now holds every pass: obfuscation, protection, resources, optimization, and reports.

## Example

```yaml
input: "my-app.jar"
output: "my-app-protected.jar"

dictionary: "alphabet"
libs: "libs/"

inclusions: []
exclusions:
  - "com\\.example\\.api\\..*"

transformers:
  class-rename:
    enabled: true
    mode: "safe"

  string-encryption:
    enabled: true
    mode: "lite"

  watermark:
    enabled: true
    owner: "Example Studios"
    id: "customer-42"

  integrity:
    enabled: true

  statistics-report:
    enabled: true
    format: "json"
    output: "build/frost-report.json"

mapping:
  enabled: true
  output: "mapping.txt"
```

## Global Settings

| Setting | Type | Description |
|---|---|---|
| `input` | String | Source JAR. |
| `output` | String | Output JAR. |
| `dictionary` | String | Naming dictionary for renaming passes. |
| `package-mode` | String | Package handling: `keep`, `flatten`, or `remove`. |
| `flatten-package` | String | Package name used when `package-mode` is `flatten`. |
| `libs` | String | Dependency JAR directory for hierarchy checks. |
| `exclusions` | List<String> | Regex patterns for classes to skip. |
| `inclusions` | List<String> | Regex patterns for classes to process. |

## Notes

- Keep exclusions for reflection, JNI, serialization, plugin entry points, and public APIs.
- `resource-compression.remove-originals` removes protected resource originals after compressed copies are written.
- `anti-debug` should be tested carefully because it changes runtime startup behavior.
