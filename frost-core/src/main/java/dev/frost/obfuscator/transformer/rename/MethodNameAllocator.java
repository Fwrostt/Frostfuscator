package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.dictionary.Dictionary;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared method-name allocation policy for renamed and generated methods.
 */
public final class MethodNameAllocator {
    private static final int MAX_DICTIONARY_ATTEMPTS = 10_000;

    private final Dictionary dictionary;
    private final String dictionaryType;
    private final Map<String, Set<String>> usedNamesPerClass = new HashMap<>();

    public MethodNameAllocator(String dictionaryType, Collection<ClassNode> classes) {
        this.dictionaryType = normalizedDictionaryType(dictionaryType);
        dictionary = Dictionary.create(this.dictionaryType);
        for (ClassNode classNode : classes) {
            Set<String> used = usedNamesPerClass.computeIfAbsent(classNode.name, ignored -> new HashSet<>());
            for (MethodNode method : classNode.methods) {
                used.add(methodKey(method.name, method.desc));
            }
        }
    }

    public String next(String owner, String desc) {
        return next(Set.of(owner), desc);
    }

    public String next(Set<String> owners, String desc) {
        for (int attempt = 0; attempt < MAX_DICTIONARY_ATTEMPTS; attempt++) {
            String candidate = dictionary.next();
            if (reserve(candidate, desc, owners)) {
                return candidate;
            }
        }

        int suffix = 0;
        while (true) {
            String candidate = "frost$m" + Integer.toUnsignedString(suffix++, 36);
            if (reserve(candidate, desc, owners)) {
                Logger.warn("[method-names] Dictionary stopped producing unique names; using bounded fallback names");
                return candidate;
            }
        }
    }

    public int ownerCount() {
        return usedNamesPerClass.size();
    }

    public boolean usesDictionary(String candidate) {
        return dictionaryType.equals(normalizedDictionaryType(candidate));
    }

    private boolean reserve(String candidate, String desc, Set<String> owners) {
        if (candidate == null || candidate.isEmpty()) return false;
        String key = methodKey(candidate, desc);
        for (String owner : owners) {
            if (usedNamesPerClass.getOrDefault(owner, Set.of()).contains(key)) return false;
        }
        for (String owner : owners) {
            usedNamesPerClass.computeIfAbsent(owner, ignored -> new HashSet<>()).add(key);
        }
        return true;
    }

    private static String methodKey(String name, String desc) {
        return name + desc;
    }

    private static String normalizedDictionaryType(String dictionaryType) {
        return dictionaryType == null ? "alphabet" : dictionaryType.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
