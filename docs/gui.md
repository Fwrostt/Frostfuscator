# GUI Usage Guide

Frostfuscator includes a full graphical interface built with JavaFX, making it incredibly easy to configure your obfuscation parameters without writing YAML by hand.

## Launching the GUI

The GUI application is packaged in `Frostfuscator-gui.jar`.

```bash
java -jar Frostfuscator-gui.jar
```

## Features of the GUI

1. **Visual Transformer Selection**: A list of all available transformers is displayed on the screen. You can simply check the boxes next to the ones you wish to enable.
2. **File Pickers**: Easily browse your file system to select the Input JAR, Output JAR, and Library Directory.
3. **Dictionary Selection**: A dropdown menu allows you to select between `alphabet`, `numeric`, `unicode`, or a custom file.
4. **Configuration Export**: Once you have visually configured everything to your liking, you can export the setup directly to a `config.yml` file for future CLI use.
5. **One-Click Obfuscation**: Hit the "Start Obfuscation" button to execute the obfuscation engine directly from the GUI. Console logs and progress will be shown in the UI.

## Typical Workflow

1. Open `Frostfuscator-gui.jar`.
2. Click **Browse** next to Input File and select your application JAR.
3. Select your desired naming dictionary from the dropdown.
4. Go through the Transformer list and check `StringEncryptionTransformer`, `RemoveDebugTransformer`, and `FlowObfuscationTransformer`.
5. Click **Start Obfuscation**.
6. (Optional) Click **Export Config** to save your settings to `my-config.yml` so you can use it in your build scripts later.
