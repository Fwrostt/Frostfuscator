package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.util.ClassHierarchy;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;

public class ClassPool {

    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, ClassNode> libraryClasses = new LinkedHashMap<>();
    private final Map<String, String> originalNames = new LinkedHashMap<>();
    private final Set<String> dirtyClasses = new HashSet<>();
    private final ClassHierarchy hierarchy = new ClassHierarchy();
    private List<String> globalExclusions = new ArrayList<>();
    private List<String> globalInclusions = new ArrayList<>();
    private String packageMode = "keep";
    private String flattenPackage = "obf";

    public void addClass(String name, ClassNode node) {
        classes.put(name, node);
        originalNames.put(name, name);
    }

    public void addLibraryClass(String name, ClassNode node) {
        libraryClasses.put(name, node);
    }

    public ClassNode getClass(String name) {
        return classes.get(name);
    }

    public Collection<ClassNode> getClasses() {
        return classes.values();
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
    }

    public void replace(String oldName, String newName, ClassNode node) {
        classes.remove(oldName);
        classes.put(newName, node);
        String originalName = originalNames.remove(oldName);
        originalNames.put(newName, originalName != null ? originalName : oldName);
        dirtyClasses.remove(oldName);
        dirtyClasses.add(newName);
    }

    public void setOriginalName(String currentName, String originalName) {
        originalNames.put(currentName, originalName);
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
        Map<String, ClassNode> all = new LinkedHashMap<>();
        all.putAll(libraryClasses);
        all.putAll(classes);
        hierarchy.build(all, new java.util.HashSet<>(classes.keySet()));
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
