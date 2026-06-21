# GUI Usage

The GUI is packaged as `build/libs/Frostfuscator-gui.jar`.

```bash
java -jar build/libs/Frostfuscator-gui.jar
```

On Windows, use `build/libs/Frostfuscator-gui.cmd` for the smoothest double-click launch. It prefers a Java 21 install when one is available and starts the GUI without a console window.

If double-clicking the JAR itself does nothing, Windows is usually pointing `.jar` files at the wrong Java runtime. Run `build/libs/Frostfuscator-gui-debug.cmd`; it keeps a console open and the GUI also writes startup failures to `%USERPROFILE%\.frostfuscator\gui-crash.log`.

## Layout

- **Project:** input/output JARs, libraries, mapping path, launch profile, and rule editor.
- **Obfuscation:** renaming, encryption, flow, call hiding, debug cleanup, and metadata passes.
- **Protection:** watermarking, integrity, anti-debug, and anti-decompiler passes.
- **Native Protection / FrostJNI:** compiler detection, install links, loader settings, and native method selection.
- **Resources:** resource compression settings.
- **Funsies:** banner injection, Emoji Hell, copypasta strings, fake application profiles, Chinese Mode, and other fun noise passes.
- **Optimize:** bytecode cleanup and shrinking.
- **Reports:** JSON/HTML statistics export.
- **Console:** live run output.

The app uses a custom OLED frame, compact top navigation, and category pages. The Project page is kept short enough to fit the default window; long pass and settings lists scroll inside their own panels.

## Workflow

1. Pick the input JAR and output path.
2. Choose **No Passes**, **Basic**, **Balanced**, **Strong**, or **Maximum** on the Project page.
3. Open category pages and adjust individual passes.
4. Open **FrostJNI** only when you want JNI conversion. Use **Detect Compilers** first; the page shows detected Clang, GCC/MinGW, or MSVC toolchains and has install buttons for common Windows setups. Enabling it shows a warning because native builds are platform dependent and require a C++ compiler.
5. Click **Run Build**.
6. Test the output JAR before keeping the config.
