# Frostfuscator Docs

Frostfuscator is centered on Java bytecode obfuscation, with extra protection, resource, optimization, reporting, and plugin features around it.

## Pages

- [Configuration](configuration.md)
- [Passes](transformers.md)
- [CLI](cli.md)
- [GUI](gui.md)
- [Plugins](plugins.md)

## How A Run Works

1. The input JAR is loaded.
2. Enabled passes run against classes and resources.
3. Renaming mappings are applied when renaming passes are enabled.
4. Post-remap passes, such as reports, run.
5. Optional FrostJNI native protection converts selected post-obfuscation methods into JNI stubs and embeds native libraries.
6. Frostfuscator writes the output JAR, optional mapping file, and optional report.

The GUI defaults to **No Passes** so a new project starts from a safe baseline. Obfuscation presets can then be enabled as needed.

## Plugin API

Custom passes can live in `plugins/` as Frostfuscator plugin jars. See [Plugins](plugins.md).

## Requirements

- Java 21 or newer.
- Enough memory for the input JAR.
- A C++ compiler when FrostJNI native protection is enabled.
