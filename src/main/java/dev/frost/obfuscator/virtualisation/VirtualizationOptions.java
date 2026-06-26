package dev.frost.obfuscator.virtualisation;

import dev.frost.obfuscator.transformer.TransformerConfig;

public record VirtualizationOptions(
        int seed,
        int minInstructions,
        int maxInstructions,
        int probability,
        boolean skipInitializers,
        boolean encryptBytecode,
        int maxLocals,
        int maxStack
) {
    public static VirtualizationOptions from(TransformerConfig config) {
        return new VirtualizationOptions(
                intOption(config, 0, "seed"),
                intOption(config, 8, "min-method-instructions", "minMethodInstructions"),
                intOption(config, 300, "max-method-instructions", "maxMethodInstructions"),
                intOption(config, 15, "probability"),
                booleanOption(config, true, "skip-initializers", "skipInitializers"),
                booleanOption(config, true, "encrypt-bytecode", "encryptBytecode"),
                intOption(config, 256, "max-locals", "maxLocals"),
                intOption(config, 512, "max-stack", "maxStack")
        );
    }

    private static int intOption(TransformerConfig config, int defaultValue, String... keys) {
        for (String key : keys) {
            Object value = config.getOptions().get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                return Integer.parseInt(value.toString());
            }
        }
        return defaultValue;
    }

    private static boolean booleanOption(TransformerConfig config, boolean defaultValue, String... keys) {
        for (String key : keys) {
            Object value = config.getOptions().get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value != null) {
                return Boolean.parseBoolean(value.toString());
            }
        }
        return defaultValue;
    }
}
