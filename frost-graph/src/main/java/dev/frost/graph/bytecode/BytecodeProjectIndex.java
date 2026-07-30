package dev.frost.graph.bytecode;

import dev.frost.graph.GraphBuildContext;
import org.objectweb.asm.ClassReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable, reusable index of classes, methods, dependencies, and calls in a project. */
public final class BytecodeProjectIndex {
    private final List<BytecodeClassInfo> classes;
    private final Map<String, BytecodeClassInfo> byName;
    private final Map<String, ClassReferences> references;

    private BytecodeProjectIndex(List<BytecodeClassInfo> classes, Map<String, BytecodeClassInfo> byName,
                                 Map<String, ClassReferences> references) {
        this.classes = List.copyOf(classes);
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
        this.references = Collections.unmodifiableMap(new TreeMap<>(references));
    }

    static BytecodeProjectIndex build(BytecodeProject project, GraphBuildContext context) {
        Map<String, ClassReferences> references = new TreeMap<>();
        List<BytecodeClassInfo> classes = new ArrayList<>();
        Map<String, BytecodeClassInfo> byName = new LinkedHashMap<>();
        int index = 0;
        for (String name : project.classNames()) {
            context.cancellation().throwIfCancelled();
            ClassReferences refs = new ClassReferences();
            new ClassReader(project.bytesUnsafe(name)).accept(refs,
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            references.put(name, refs);
            List<BytecodeMethodInfo> methods = refs.methods.stream()
                    .map(method -> new BytecodeMethodInfo(name, method.name(), method.descriptor(), method.access()))
                    .sorted(java.util.Comparator.comparing(BytecodeMethodInfo::displayName)
                            .thenComparing(BytecodeMethodInfo::descriptor)).toList();
            BytecodeClassInfo info = new BytecodeClassInfo(name, BytecodeGraphs.simple(name),
                    BytecodeGraphs.packageName(name), project.isLibrary(name), methods);
            classes.add(info);
            byName.put(name, info);
            context.progress().onProgress(++index, project.size(), "Indexing " + name);
        }
        classes.sort(java.util.Comparator.comparing(BytecodeClassInfo::qualifiedName));
        return new BytecodeProjectIndex(classes, byName, references);
    }

    public List<BytecodeClassInfo> classes() {
        return classes;
    }

    public Optional<BytecodeClassInfo> findClass(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        BytecodeClassInfo exact = byName.get(name.replace('.', '/'));
        if (exact != null) return Optional.of(exact);
        return classes.stream().filter(item -> item.displayName().equals(name)
                || item.qualifiedName().equals(name)).findFirst();
    }

    Map<String, ClassReferences> references() {
        return references;
    }
}
