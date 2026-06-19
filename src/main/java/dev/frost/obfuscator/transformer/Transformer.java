package dev.frost.obfuscator.transformer;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.util.Logger;

import java.util.List;
import java.util.regex.Pattern;

public abstract class Transformer {

    public abstract String getName();

    public boolean runsPostRemap() {
        return false;
    }

    public abstract void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config);

    protected boolean shouldProcess(String name, TransformerConfig config, List<String> globalExclusions, List<String> globalInclusions) {
        String dotName = name.replace('/', '.');

        if (globalExclusions != null) {
            for (String pattern : globalExclusions) {
                if (matches(dotName, pattern)) return false;
            }
        }

        if (config.getExclusions() != null) {
            for (String pattern : config.getExclusions()) {
                if (matches(dotName, pattern) || matches(name, pattern)) return false;
            }
        }

        if (globalInclusions != null && !globalInclusions.isEmpty()) {
            boolean matched = false;
            for (String pattern : globalInclusions) {
                if (matches(dotName, pattern)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }

        if (config.getInclusions() != null && !config.getInclusions().isEmpty()) {
            boolean matched = false;
            for (String pattern : config.getInclusions()) {
                if (matches(dotName, pattern) || matches(name, pattern)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }

        return true;
    }

    protected boolean isExcluded(String name, TransformerConfig config, List<String> globalExclusions) {
        return !shouldProcess(name, config, globalExclusions, null);
    }

    protected boolean isExcludedMember(String memberName, TransformerConfig config) {
        if (config.getExclusions() != null) {
            for (String pattern : config.getExclusions()) {
                if (matches(memberName, pattern)) return true;
            }
        }
        return false;
    }

    protected boolean matches(String input, String pattern) {
        try {
            return Pattern.matches(pattern, input);
        } catch (Exception e) {
            return input.equals(pattern) || input.contains(pattern);
        }
    }

    protected void log(String message, Object... args) {
        Logger.info("[" + getName() + "] " + message, args);
    }
}
