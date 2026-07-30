# GUI Usage

The GUI is packaged as `frost-gui/build/libs/Frostfuscator-gui.jar`.

```bash
./gradlew runGui
```

The root `runGui` task delegates to the GUI module, compiles anything stale, and starts the desktop application. To launch the packaged JAR instead:

```bash
java -jar frost-gui/build/libs/Frostfuscator-gui.jar
```

On Windows, use `frost-gui/build/libs/Frostfuscator-gui.cmd` for the smoothest double-click launch. It prefers a Java 21 install when one is available and starts the GUI without a console window.

If double-clicking the JAR itself does nothing, Windows is usually pointing `.jar` files at the wrong Java runtime. Run `frost-gui/build/libs/Frostfuscator-gui-debug.cmd`; it keeps a console open. Every GUI failure also gets a separate timestamped log in `%APPDATA%\.frostfuscator\logs\crashes`.

## Application data and startup

All desktop-owned data is kept below `%APPDATA%\.frostfuscator` on Windows. This includes:

- appearance, density, font scale, reduced-motion, window, sidebar, navigation, console, and file-dialog preferences;
- custom themes and recent projects;
- the autosaved working configuration, selected profile, optimization goal, and project limits;
- build history, the latest console session, timestamped build logs in `logs/builds`, and per-crash logs in `logs/crashes`.

The **Settings > Application data** section shows the active folder and lets users choose a different storage directory or restore the default. Existing data is copied to the chosen location, the old copy is retained as a backup, and the new location takes effect after Frostfuscator restarts.

Older GUI preferences are migrated once from the Windows Java Preferences store. User-selected output JARs, mapping files, manually saved configurations, and exported logs remain at the locations selected by the user.

The startup screen restores the workspace, loads fonts, constructs and CSS/layout-warms every page, and re-analyzes a valid previously selected input JAR before revealing the shell. Page navigation therefore reuses prepared scene graphs instead of constructing them on the first click.

## Layout

- **Project:** input/output JARs, libraries, library strict/runtime settings, mapping path, launch profile, and rule editor.
- **Obfuscation:** renaming, encryption, flow, call hiding, debug cleanup, and metadata passes.
- **Protection:** watermarking, integrity, anti-debug, and anti-decompiler passes.
- **Native Protection / FrostJNI:** compiler detection, install links, loader settings, and native method selection.
- **Resources:** resource compression settings.
- **Funsies:** banner injection, Emoji Hell, copypasta strings, fake application profiles, Chinese Mode, and other fun noise passes.
- **Optimize:** bytecode cleanup and shrinking.
- **Reports:** JSON/HTML statistics export.
- **Console:** live run output.
- **Graphs:** dependency, call, inheritance, package, CFG, transformer, mapping, and completed-build exploration with search, filters, details, zoom/pan, limits, and export.

The app uses a custom OLED frame, compact top navigation, and category pages. The Project page is kept short enough to fit the default window; long pass and settings lists scroll inside their own panels.

## Workflow

1. Pick the input JAR and output path.
2. Choose **No Passes**, **Basic**, **Balanced**, **Strong**, or **Maximum** on the Project page.
3. Use **Edit Rules** to add exact class/package inclusion and exclusion rules from the input jar, or type regexes manually.
4. Open category pages and adjust individual passes.
5. Open **FrostJNI** only when you want JNI conversion. Use **Detect Compilers** first; the page shows detected Clang, GCC/MinGW, or MSVC toolchains and has install buttons for common Windows setups. Enabling it shows a warning because native builds are platform dependent and require a C++ compiler.
6. Click **Run Build**.
7. Test the output JAR before keeping the config.

## Graph Explorer

The Graphs page is initialized only when opened. A reusable class/method index and focused graph projection run in a cancellable background worker, while Cytoscape renders from a bundled local runtime in an isolated WebView. Leaving the page releases WebKit, archive bytes, index, cache entries, and the worker to keep idle memory bounded. See [Graph Visualization](graphs.md) for all controls and formats.
