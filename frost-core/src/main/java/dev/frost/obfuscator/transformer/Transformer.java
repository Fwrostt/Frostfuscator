package dev.frost.obfuscator.transformer;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.util.Logger;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public abstract class Transformer {

    public enum Priority {
        PRE_OBFUSCATION,
        PRE_RENAME,
        NORMAL,
        POST_FLOW,
        POST_REMAP,
        FINAL,
        CLASSLOADER_ENCRYPTION
    }

    public abstract String getName();

    /** Stable graph/config identifier. Existing transformers use their registry name. */
    public String graphId() { return getName(); }

    /** Transformer ids which must execute first. */
    public Set<String> dependencies() { return Set.of(); }

    /** Transformer ids which should not be enabled together. */
    public Set<String> conflicts() { return Set.of(); }

    /** Lower values execute first within the same engine priority group. */
    public int orderWeight() { return 0; }

    public String getCategory() {
        return "Obfuscation";
    }

    public boolean runsPostRemap() {
        return false;
    }

    public Priority priority() {
        return runsPostRemap() ? Priority.POST_REMAP : Priority.NORMAL;
    }

    public void transform(Context context) {
        transform(context.pool(), context.mappings(), context.config());
    }

    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
    }

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

    protected void detail(String message, Object... args) {
        Logger.debug("[" + getName() + "] " + message, args);
    }
}
