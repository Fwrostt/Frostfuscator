# GUI Usage

The GUI is packaged as `Frostfuscator-gui.jar`.

```bash
java -jar Frostfuscator-gui.jar
```

## Layout

- **Project:** input/output JARs, libraries, mapping path, launch profile, and rule editor.
- **Obfuscation:** renaming, encryption, flow, call hiding, debug cleanup, and metadata passes.
- **Protection:** watermarking, integrity, anti-debug, and anti-decompiler passes.
- **Resources:** resource compression settings.
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
