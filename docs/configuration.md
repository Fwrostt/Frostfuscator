# Configuration

Frostfuscator uses YAML for paths, filters, mappings, and pass settings.

The key is still named `transformers:` for compatibility, but it now holds every pass: obfuscation, protection, resources, optimization, and reports.

## Example

```yaml
input: "my-app.jar"
output: "my-app-protected.jar"

dictionary: "alphabet"
libs: "libs/"
libraries:
  paths:
    - "libs"
    - "third-party/api.jar"
  recursive: true
  runtime: true
  strict: false
seed: 0
plugins:
  - "plugins"

inclusions: []
exclusions:
  - "com\\.example\\.api\\..*"

frostjni:
  enabled: false
  outputLibraryName: "frostjni_protected"
  useClang: true
  useGcc: true
  useMsvc: true
  mode: "SELECTIVE"
  compileMode: "FAST"
  unityBuild: true
  optimizationLevel: "O0"
  stripSymbols: false
  includePackages:
    - "com.example.security"
  includeClasses: []
  includeMethods: []
  includeAnnotations: []
  excludedClasses:
    - "com.example.Main"
  excludedPackages: []
  excludedAnnotations: []
  resourceEmbedding: true
  keepGeneratedSources: false
  failFast: true
  continueOnFailure: false

transformers:
  license-guard:
    enabled: false
    product: "ExampleProduct"
    license-id: "customer-42"
    expires-at: "2026-12-31"
    not-before: ""
    grace-days: 0
    hwid-enabled: false
    bind-current-machine: false
    allowed-hwids: ""
    hwid-components: "mac,hostname,os,user,machine-id"
    hwid-salt: "change-me"
    token: ""
    token-public-key: ""
    required-features: ""
    clock-rollback: true
    failure-action: "throw"
    coverage: "entrypoints"
    inject-clinit: true

  class-rename:
    enabled: true
    mode: "safe"

  string-encryption:
    enabled: true
    mode: "lite"

  classloader-encryption:
    enabled: false
    encryptMainClass: true
    algorithm: "AES/GCM/NoPadding"
    resourcePath: "classes.db"
    compressClasses: true
    failOnError: true
    exclusions: []
    inclusions: []

  virtualization:
    enabled: false
    probability: 15
    min-method-instructions: 8
    max-method-instructions: 300
    skip-initializers: true
    encrypt-bytecode: true
    max-locals: 256
    max-stack: 512
    exclusions: []
    inclusions: []

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
| `libs` | String | Backward-compatible dependency path field. Accepts one path or comma/semicolon-separated paths. |
| `libraries.paths` | List<String> | Dependency JARs, ZIPs, or directories used for hierarchy and frame computation. |
| `libraries.recursive` | Boolean | Recursively scans dependency directories for `.jar` and `.zip` archives. |
| `libraries.runtime` | Boolean | Loads Java runtime module classes as library stubs, improving hierarchy and common-superclass resolution. |
| `libraries.strict` | Boolean | Fails the run when library paths or archives cannot be loaded. Use for release builds. |
| `seed` | Number | Global seed. `0` uses fresh randomness; positive values can be pushed into seed-aware passes from the CLI. |
| `plugins` | List<String> | Plugin directories scanned for Frostfuscator extension jars. |
| `exclusions` | List<String> | Regex patterns for classes to skip. |
| `inclusions` | List<String> | Regex patterns for classes to process. |

## Plugins

Frostfuscator scans `plugins/` by default and also scans directories listed in `plugins:`. Plugin jars can expose transformers through `META-INF/services/dev.frost.obfuscator.transformer.Transformer` and may include `frost-plugin.yml` for name/version metadata and an optional `main` plugin entrypoint.

## FrostJNI Native Protection

`frostjni:` is a top-level section, not a transformer. It runs after Java obfuscation passes and before the final output jar is written. Selected methods are translated to C++, compiled into a native library, replaced with Java `native` declarations, and loaded through an injected runtime loader.

| Setting | Type | Description |
|---|---|---|
| `enabled` | Boolean | Enables the native protection pipeline. |
| `outputLibraryName` | String | Base library name passed to the loader, default `frostjni_protected`. |
| `useClang` / `useGcc` / `useMsvc` | Boolean | Allows Clang, GCC/MinGW, or MSVC compiler backends. |
| `mode` | String | `SELECTIVE` converts only chosen classes/packages/methods/annotations. `FULL` converts every eligible original input class. |
| `compileMode` | String | `FAST` uses fast dev settings. `RELEASE` uses configured optimization/stripping. |
| `unityBuild` | Boolean | Compiles a generated unity source for much faster MinGW/Clang builds. |
| `optimizationLevel` | String | Reserved compiler optimization preference. |
| `stripSymbols` | Boolean | Strips native symbols in release-oriented builds when the backend supports it. |
| `generateHeaders` | Boolean | Legacy compatibility switch. Defaults to `false`; FrostJNI registers native methods internally and does not need public method headers. |
| `includePackages` | List<String> | Packages eligible for native conversion. In `SELECTIVE` mode, choose at least one class, package, method, or annotation. |
| `includeClasses` | List<String> | Exact classes eligible for native conversion. |
| `includeMethods` | List<String> | Method names or `owner#method` entries eligible for conversion. |
| `includeAnnotations` | List<String> | Annotation descriptors/classes that opt classes or methods into native conversion. |
| `excludedClasses` | List<String> | Exact classes that must stay Java. |
| `excludedPackages` | List<String> | Packages that must stay Java. |
| `excludedAnnotations` | List<String> | Annotation descriptors/classes that force Java output. |
| `temporaryDirectory` | String | Optional native work directory. Defaults beside the output jar. |
| `keepGeneratedSources` | Boolean | Keeps generated C++ sources for inspection. |
| `resourceEmbedding` | Boolean | Embeds native libraries under `native/{os}/{arch}/` in the jar. |
| `debugMode` | Boolean | Reserved for verbose native diagnostics. |
| `failFast` | Boolean | Fails the build if native conversion/compilation fails. |
| `continueOnFailure` | Boolean | Keeps Java output if native protection fails. |

## Notes

- Keep exclusions for reflection, JNI, serialization, plugin entry points, and public APIs.
- `license-guard` runs before normal obfuscation, then later rename/string/flow passes can harden the injected verifier. It supports direct expiry/not-before rules, HWID hashes, current-machine binding, optional RSA/HMAC signed license tokens, feature checks, and clock rollback state.
- For Bukkit/Paper plugins, `license-guard.coverage: entrypoints` injects into `onLoad`/`onEnable` and class initialization when those methods/classes exist, without depending on the Bukkit API at build time.
- Use `license-guard.bind-current-machine: true` only for customer-specific builds. For reusable releases, prefer `token` with `token-public-key` and put customer/HWID/feature/expiry claims in the signed token.
- `resource-compression.remove-originals` removes protected resource originals after compressed copies are written.
- `resource-encryption.remove-originals` should stay `false` unless your application knows how to decrypt resources at runtime.
- `anti-debug` should be tested carefully because it changes runtime startup behavior.
- `classloader-encryption` runs after remapping and native protection. Standalone jars get a bootstrap main class and encrypted classes are removed from the raw JAR. Bukkit/Paper plugin jars keep the plugin main class and its startup signature dependencies loadable, and only encrypt same-package classes that can be defined safely from the plugin main lookup.
- `classloader-encryption` accepts both camelCase and kebab-case aliases for common options, including `resourcePath`/`resource-path`, `failOnError`/`fail-on-error`, and `compressClasses`/`compress-classes`.
- `virtualization` skips methods with exception handlers, invokedynamic, synchronized bytecode, oversized local/stack usage, and loader/runtime classes. Raise `max-locals` and `max-stack` only after testing the protected output with your target API.
- `anti-debug.check-processes` is intentionally opt-in because it exits when common reverse-engineering tools are running.
- `anti-debug.shared-helper` keeps heavy debug checks in one generated helper class to reduce per-class decompiler bloat.
- `fake-classes.placement` supports `package-mode`, `existing`, `specific`, and `none`.
- `chinese-mode.package-mode` supports `random`, `global`, `existing`, and `none`; `global` uses `package-prefix`, while `random` creates fresh Chinese package paths.
- `fake-classes` and `fake-application` run before normal obfuscation, so enabled rename/string/flow passes also affect generated classes.
- `fake-classes.seed`, `junk-code.seed`, and `resource-encryption.seed` use fresh randomness when set to `0`.
- FrostJNI skips Frostfuscator runtime/loader classes and generated fake/helper classes by default. Exclusions always take priority over includes.
- FrostJNI requires a local C++ compiler such as Clang, MinGW GCC, or MSVC Build Tools. On Windows, MSYS2 UCRT64 MinGW works well when `g++` is available.
- Keep FrostJNI in `SELECTIVE` mode for real applications. Commercial protectors usually native-protect only high-value code such as licensing, authentication, HWID checks, or decryptors.
- FrostJNI registers converted methods internally through `JNI_OnLoad`; protected jars do not expose a `native/native-methods.txt` method map.
- `FAST` mode defaults to `O0`, no symbol stripping, and unity builds. The compiler log shows how many translation units were merged and prints periodic heartbeat messages during long native compiles.

