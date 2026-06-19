package dev.frost.obfuscator;

import dev.frost.obfuscator.config.ConfigLoader;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.engine.ObfuscationEngine;
import dev.frost.obfuscator.transformer.TransformerRegistry;
import dev.frost.obfuscator.util.Logger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "frostfuscator",
        mixinStandardHelpOptions = true,
        version = "Frostfuscator 1.0.0",
        description = "Java obfuscation toolkit"
)
public class Main implements Callable<Integer> {

    @CommandLine.Option(names = {"-i", "--input"}, description = "Input JAR")
    private String input;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Output JAR")
    private String output;

    @CommandLine.Option(names = {"-c", "--config"}, description = "YAML config file")
    private String configPath;

    @CommandLine.Option(names = {"-t", "--transforms", "--transformers"}, description = "Comma-separated transform names; overrides config")
    private String transformersList;

    @CommandLine.Option(names = {"-l", "--libs"}, description = "Folder containing dependency JARs")
    private String libs;

    @CommandLine.Option(names = {"--list-transforms", "--list-transformers"}, description = "List transforms and exit")
    private boolean listTransformers;

    @Override
    public Integer call() {
        try {
            Logger.printBanner();

            if (listTransformers) {
                Logger.info("Available transforms:");
                for (String name : TransformerRegistry.getAllNames()) {
                    Logger.info("  - {}", name);
                }
                return 0;
            }

            ObfuscationConfig config;
            if (configPath != null) {
                config = ConfigLoader.load(Path.of(configPath));
            } else {
                config = ConfigLoader.loadDefault();
            }

            ConfigLoader.applyOverrides(config, input, output, libs);
            ConfigLoader.validate(config);

            List<String> cliTransformers = null;
            if (transformersList != null && !transformersList.isEmpty()) {
                cliTransformers = Arrays.stream(transformersList.split(","))
                        .map(String::trim)
                        .toList();
                Logger.info("CLI transform override: {}", cliTransformers);
            }

            ObfuscationEngine engine = new ObfuscationEngine(config, cliTransformers);
            engine.run();

            return 0;
        } catch (Exception e) {
            Logger.error("Obfuscation failed: {}", e.getMessage());
            Logger.error("Fatal error", e);
            return 1;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
