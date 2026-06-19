package dev.frost.obfuscator.transformer;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.engine.JarProcessor;
import dev.frost.obfuscator.engine.ProtectionStats;
import dev.frost.obfuscator.remapper.MappingCollector;

import java.nio.file.Path;
import java.util.Map;

public record Context(
        ClassPool pool,
        JarProcessor jar,
        MappingCollector mappings,
        TransformerConfig config,
        ProtectionStats stats,
        Path inputPath,
        Path outputPath
) {
    public Map<String, byte[]> resources() {
        return jar.getResources();
    }
}
