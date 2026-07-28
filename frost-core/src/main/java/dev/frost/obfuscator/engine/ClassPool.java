package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.util.ClassHierarchy;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Consumer;
import java.util.function.Function;

public class ClassPool {

    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, ClassNode> libraryClasses = new LinkedHashMap<>();
    private final Map<String, String> originalNames = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> dirtyClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> generatedDecoyClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> transformationExcludedClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, String> transformationExclusionReasons = new java.util.concurrent.ConcurrentHashMap<>();
    private final ClassHierarchy hierarchy = new ClassHierarchy();
    private List<String> globalExclusions = new ArrayList<>();
    private List<String> globalInclusions = new ArrayList<>();
    private String packageMode = "keep";
    private String flattenPackage = "obf";
    private ForkJoinPool transformExecutor;
    private int parallelThreshold = 32;
    private BuildCancellation cancellation = new BuildCancellation();

    public void addClass(String name, ClassNode node) {
        classes.put(name, node);
        originalNames.put(name, name);
    }

    public void addLibraryClass(String name, ClassNode node) {
        libraryClasses.put(name, node);
    }

    public ClassNode getClass(String name) {
        ClassNode application = classes.get(name);
        return application != null ? application : libraryClasses.get(name);
    }

    public Collection<ClassNode> getClasses() {
        cancellation.throwIfCancelled();
        List<ClassNode> transformable = new ArrayList<>(classes.size() - transformationExcludedClasses.size());
        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            cancellation.throwIfCancelled();
            if (!transformationExcludedClasses.contains(entry.getKey())) {
                transformable.add(entry.getValue());
            }
        }
        List<ClassNode> snapshot = List.copyOf(transformable);
        return new AbstractCollection<>() {
            @Override
            public Iterator<ClassNode> iterator() {
                Iterator<ClassNode> delegate = snapshot.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        cancellation.throwIfCancelled();
                        return delegate.hasNext();
                    }

                    @Override
                    public ClassNode next() {
                        cancellation.throwIfCancelled();
                        return delegate.next();
                    }
                };
            }

            @Override
            public int size() {
                return snapshot.size();
            }
        };
    }

    public void setCancellation(BuildCancellation cancellation) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public BuildCancellation cancellation() {
        return cancellation;
    }

    public void configureParallelism(boolean enabled, int requestedParallelism, int minimumClasses) {
        closeParallelism();
        parallelThreshold = Math.max(1, minimumClasses);
        int available = Runtime.getRuntime().availableProcessors();
        int parallelism = requestedParallelism <= 0 ? available : Math.min(requestedParallelism, available);
        if (!enabled || parallelism <= 1) return;
        transformExecutor = new ForkJoinPool(parallelism, pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("frost-transform-" + worker.getPoolIndex());
            return worker;
        }, null, false);
    }

    public void forEachClass(Consumer<? super ClassNode> operation) {
        Objects.requireNonNull(operation, "operation");
        List<ClassNode> snapshot = orderedClassSnapshot();
        Consumer<ClassNode> cancellableOperation = classNode -> {
            cancellation.throwIfCancelled();
            operation.accept(classNode);
            cancellation.throwIfCancelled();
        };
        if (transformExecutor == null || snapshot.size() < parallelThreshold) {
            snapshot.forEach(cancellableOperation);
            return;
        }
        transformExecutor.submit(() -> snapshot.parallelStream().forEach(cancellableOperation)).join();
    }

    public <T> List<T> mapClasses(Function<? super ClassNode, T> operation) {
        Objects.requireNonNull(operation, "operation");
        List<ClassNode> snapshot = orderedClassSnapshot();
        Function<ClassNode, T> cancellableOperation = classNode -> {
            cancellation.throwIfCancelled();
            T result = operation.apply(classNode);
            cancellation.throwIfCancelled();
            return result;
        };
        if (transformExecutor == null || snapshot.size() < parallelThreshold) {
            return snapshot.stream().map(cancellableOperation).toList();
        }
        return transformExecutor.submit(() -> snapshot.parallelStream().map(cancellableOperation).toList()).join();
    }

    public boolean isParallelTransformEnabled() {
        return transformExecutor != null;
    }

    public int transformParallelism() {
        return transformExecutor == null ? 1 : transformExecutor.getParallelism();
    }

    public void closeParallelism() {
        if (transformExecutor != null) {
            transformExecutor.shutdownNow();
            transformExecutor = null;
        }
    }

    private List<ClassNode> orderedClassSnapshot() {
        cancellation.throwIfCancelled();
        List<ClassNode> snapshot = new ArrayList<>(getClasses());
        snapshot.sort(Comparator.comparing(node -> node.name));
        return snapshot;
    }

    public Map<String, ClassNode> getClassMap() {
        return classes;
    }

    public Map<String, ClassNode> getLibraryClasses() {
        return libraryClasses;
    }

    public int size() {
        return classes.size();
    }

    public boolean contains(String name) {
        return classes.containsKey(name);
    }

    public void remove(String name) {
        classes.remove(name);
        transformationExcludedClasses.remove(name);
        transformationExclusionReasons.remove(name);
    }

    public void replace(String oldName, String newName, ClassNode node) {
        classes.remove(oldName);
        classes.put(newName, node);
        String originalName = originalNames.remove(oldName);
        originalNames.put(newName, originalName != null ? originalName : oldName);
        String exclusionReason = transformationExclusionReasons.remove(oldName);
        if (transformationExcludedClasses.remove(oldName)) {
            transformationExcludedClasses.add(newName);
            if (exclusionReason != null) transformationExclusionReasons.put(newName, exclusionReason);
        }
        dirtyClasses.remove(oldName);
        dirtyClasses.add(newName);
    }

    public void setOriginalName(String currentName, String originalName) {
        originalNames.put(currentName, originalName);
        if (generatedDecoyClasses.contains(originalName)) {
            generatedDecoyClasses.add(currentName);
        }
        if (transformationExcludedClasses.contains(originalName)) {
            excludeFromTransformation(currentName,
                    transformationExclusionReasons.getOrDefault(originalName, "library class"));
        }
    }

    public boolean excludeFromTransformation(String className, String reason) {
        if (!classes.containsKey(className)) return false;
        transformationExclusionReasons.putIfAbsent(className,
                reason == null || reason.isBlank() ? "library class" : reason);
        return transformationExcludedClasses.add(className);
    }

    public boolean isTransformationExcluded(String className) {
        return transformationExcludedClasses.contains(className);
    }

    public int transformationExcludedSize() {
        return transformationExcludedClasses.size();
    }

    public int transformableSize() {
        return classes.size() - transformationExcludedClasses.size();
    }

    public Map<String, String> getTransformationExclusions() {
        return Collections.unmodifiableMap(new TreeMap<>(transformationExclusionReasons));
    }

    public void markGeneratedDecoy(String className) {
        generatedDecoyClasses.add(className);
    }

    public boolean isGeneratedDecoy(String className) {
        return generatedDecoyClasses.contains(className);
    }

    public String getOriginalName(String currentName) {
        return originalNames.getOrDefault(currentName, currentName);
    }

    public void markDirty(String currentName) {
        dirtyClasses.add(currentName);
    }

    public boolean isDirty(String currentName) {
        return dirtyClasses.contains(currentName);
    }

    public void buildHierarchy() {
        cancellation.throwIfCancelled();
        Map<String, ClassNode> all = new LinkedHashMap<>();
        all.putAll(libraryClasses);
        all.putAll(classes);
        Set<String> applicationClasses = new HashSet<>(classes.keySet());
        applicationClasses.removeAll(transformationExcludedClasses);
        hierarchy.build(all, applicationClasses);
    }

    public ClassHierarchy getHierarchy() {
        return hierarchy;
    }

    public List<String> getGlobalExclusions() {
        return globalExclusions;
    }

    public void setGlobalExclusions(List<String> exclusions) {
        this.globalExclusions = exclusions != null ? exclusions : new ArrayList<>();
    }

    public List<String> getGlobalInclusions() {
        return globalInclusions;
    }

    public void setGlobalInclusions(List<String> inclusions) {
        this.globalInclusions = inclusions != null ? inclusions : new ArrayList<>();
    }

    public String getPackageMode() {
        return packageMode;
    }

    public void setPackageMode(String packageMode) {
        this.packageMode = packageMode;
    }

    public String getFlattenPackage() {
        return flattenPackage;
    }

    public void setFlattenPackage(String flattenPackage) {
        this.flattenPackage = flattenPackage;
    }

    public int librarySize() {
        return libraryClasses.size();
    }
}
