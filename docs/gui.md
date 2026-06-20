# GUI Usage

The GUI is packaged as `build/libs/Frostfuscator-gui.jar`.

```bash
java -jar build/libs/Frostfuscator-gui.jar
```

On Windows, the same JAR can be opened by double-clicking it when Java 17 or newer is installed and `.jar` files are associated with Java.

## Layout

- **Project:** input/output JARs, libraries, mapping path, launch profile, and rule editor.
- **Obfuscation:** renaming, encryption, flow, call hiding, debug cleanup, and metadata passes.
- **Protection:** watermarking, integrity, anti-debug, and anti-decompiler passes.
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
4. Click **Run Build**.
5. Test the output JAR before keeping the config.
