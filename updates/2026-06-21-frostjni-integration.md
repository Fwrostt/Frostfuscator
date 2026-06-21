# 2026-06-21 FrostJNI Integration

- Integrated FrostJNI directly into Frostfuscator under `dev.frost.obfuscator.jni`.
- Added a top-level `frostjni` configuration object and YAML serialization.
- Added native generation, compiler detection/build, method patching, loader injection, and native resource embedding to the normal obfuscation pipeline.
- Added a dedicated GUI page for Native Protection with a warning confirmation before enabling.
- Added runtime loader classes and embedded library layout under `native/{os}/{arch}/`.
- Added native metrics to protection stats and refreshed reports after native protection runs.
- Updated documentation for Java 21, FrostJNI configuration, GUI usage, CLI usage, and toolchain requirements.
- Verified an integrated smoke jar that converts `SmokeApp.main` and `SmokeApp.message` to native methods, embeds `frostjni_protected.dll`, and runs successfully.

## Native UX Follow-Up

- Reworked the GUI FrostJNI page into a compact scrollable layout so it no longer pushes the title/header area out of view.
- Added compiler detection directly in the GUI with beginner-friendly install buttons for MinGW, MSVC Build Tools, and Clang.
- Added `useMsvc` to the FrostJNI config and exposed Clang, GCC/MinGW, and MSVC as selectable compiler families.
- Added GUI validation warnings when FrostJNI is enabled but no selected compiler is detected.

## Native Compile Performance Follow-Up

- Made `SELECTIVE` + `FAST` the practical default for FrostJNI so users protect chosen classes/packages instead of accidentally converting whole applications.
- Added unity-build compilation by default for fast native smoke/release iteration.
- Fixed generated unity builds by giving each generated class its own native cache globals.
- Fixed compiler process handling so stdout and stderr are drained concurrently instead of risking a silent deadlock.
- Added compiler heartbeat logs and clearer unity-build logs, including how many translation units were merged.
- Verified a selective native smoke jar that compiles with MSYS2 MinGW, embeds the DLL, strips Java method bodies to native stubs, and runs successfully.

## Native Overload Fix

- Fixed JNI symbol generation for overloaded Java methods by emitting descriptor-qualified long JNI names.
- Verified overload protection with two native `choose(...)` overloads in one class.
- Removed MinGW label-at-end warning spam by emitting an empty statement after generated C++ labels.
- Made fallback mode warn that the output jar is Java-only when native compilation fails and `continueOnFailure` or disabled fail-fast allows the build to continue.

## String Concat Invokedynamic Coverage

- Added bootstrap metadata to FrostJNI's ASM-independent invokedynamic model.
- Implemented native lowering for `java/lang/invoke/StringConcatFactory` invokedynamic calls using JNI `StringBuilder`.
- Converted Java 9+ string concatenation methods that previously stayed Java due to invokedynamic.
- Verified protected jars where `main` and helper methods containing string concat are stripped to native stubs and still execute correctly.

## Real-World Bytecode Coverage Follow-Up

- Added `LambdaMetafactory` desugaring so Java lambda invokedynamic sites become generated helper classes before native conversion.
- Added `ObjectMethods` desugaring so record-style `toString`, `hashCode`, and `equals` methods can be native-protected.
- Added JVM opcode coverage for object branches, primitive conversions, primitive comparisons, bit operations, unsigned shifts, and common stack shuffles.
- Replaced generic native skip warnings with exact unsupported opcode/bootstrap diagnostics.
- Verified the FrostCore package native pass with 2,413 converted native methods, 0 conversion failures, one embedded Windows x86_64 DLL, and no unsupported generated-source placeholders.

## GUI Launch Follow-Up

- Added a double-click friendly `Frostfuscator-gui.cmd` launcher beside the GUI jar.
- Added `Frostfuscator-gui-debug.cmd` for users whose Windows `.jar` association points at the wrong Java runtime.
- Added GUI startup crash logging to `%USERPROFILE%\.frostfuscator\gui-crash.log` with a visible error dialog when JavaFX startup fails.
