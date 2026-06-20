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

  anti-debug:
    enabled: true
    check-arguments: true
    check-debug-classes: true
    check-stack: true
    check-timing: true
    check-processes: false

  junk-code:
    enabled: true
    min-methods-per-class: 1
    max-methods-per-class: 2
    min-fields-per-class: 0
    max-fields-per-class: 1

  fake-classes:
    enabled: false
    count: 12
    min-methods-per-class: 8
    max-methods-per-class: 16
    kind-ratio: "regular:70,interface:10,enum:10,inner:10"
    placement: "package-mode"
    naming: "dictionary"

  fake-application:
    enabled: false
    profiles: "minecraft-plugin,networking-stack,enterprise"
    classes-per-profile: 3

  inject-banner:
    enabled: false
    text: "Protected by Frostfuscator"

  emoji-hell:
    enabled: false

  copypasta-injector:
    enabled: false

  chinese-mode:
    enabled: false
    package-mode: "random"
    package-prefix: "冰霜/混淆器"
    large-banners: true
    quotes: true

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
- `resource-encryption.remove-originals` should stay `false` unless your application knows how to decrypt resources at runtime.
- `anti-debug` should be tested carefully because it changes runtime startup behavior.
- `anti-debug.check-processes` is intentionally opt-in because it exits when common reverse-engineering tools are running.
- `anti-debug.shared-helper` keeps heavy debug checks in one generated helper class to reduce per-class decompiler bloat.
- `fake-classes.placement` supports `package-mode`, `existing`, `specific`, and `none`.
- `chinese-mode.package-mode` supports `random`, `global`, `existing`, and `none`; `global` uses `package-prefix`, while `random` creates fresh Chinese package paths.
- `fake-classes` and `fake-application` run before normal obfuscation, so enabled rename/string/flow passes also affect generated classes.
- `fake-classes.seed`, `junk-code.seed`, and `resource-encryption.seed` use fresh randomness when set to `0`.

