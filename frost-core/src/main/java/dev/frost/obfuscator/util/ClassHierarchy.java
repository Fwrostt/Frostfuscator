package dev.frost.obfuscator.util;

import org.objectweb.asm.tree.ClassNode;

import java.util.*;

public class ClassHierarchy {

    private final Map<String, Set<String>> parentMap = new HashMap<>();
    private final Map<String, Set<String>> childrenMap = new HashMap<>();
    private final Map<String, ClassNode> nodeMap = new HashMap<>();
    private final Set<String> appClasses = new HashSet<>();

    public void build(Map<String, ClassNode> classes, Set<String> applicationClasses) {
        nodeMap.putAll(classes);
        appClasses.addAll(applicationClasses);

        for (ClassNode classNode : classes.values()) {
            Set<String> parents = new HashSet<>();

            if (classNode.superName != null) {
                parents.add(classNode.superName);
                childrenMap.computeIfAbsent(classNode.superName, k -> new HashSet<>()).add(classNode.name);
            }

            if (classNode.interfaces != null) {
                for (String iface : classNode.interfaces) {
                    parents.add(iface);
                    childrenMap.computeIfAbsent(iface, k -> new HashSet<>()).add(classNode.name);
                }
            }

            parentMap.put(classNode.name, parents);
        }
    }

    public Set<String> getParents(String className) {
        return parentMap.getOrDefault(className, Collections.emptySet());
    }

    public Set<String> getAllParents(String className) {
        Set<String> result = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(getParents(className));
        while (!queue.isEmpty()) {
            String parent = queue.poll();
            if (result.add(parent)) {
                queue.addAll(getParents(parent));
            }
        }
        return result;
    }

    public Set<String> getChildren(String className) {
        return childrenMap.getOrDefault(className, Collections.emptySet());
    }

    public Set<String> getAllChildren(String className) {
        Set<String> result = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(getChildren(className));
        while (!queue.isEmpty()) {
            String child = queue.poll();
            if (result.add(child)) {
                queue.addAll(getChildren(child));
            }
        }
        return result;
    }

    public Set<String> getOverrideGroup(String owner, String name, String desc) {
        Set<String> group = new HashSet<>();
        group.add(owner);

        Deque<String> queue = new ArrayDeque<>();
        queue.add(owner);

        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;

            for (String parent : getParents(current)) {
                if (hasMethod(parent, name, desc)) {
                    group.add(parent);
                }
                if (!parent.equals("java/lang/Object")) {
                    queue.add(parent);
                }
            }

            if (!current.equals("java/lang/Object")) {
                for (String child : getChildren(current)) {
                    if (hasMethod(child, name, desc)) {
                        group.add(child);
                    }
                    queue.add(child);
                }
            }
        }

        return group;
    }

    public boolean hasMethod(String className, String name, String desc) {
        ClassNode node = nodeMap.get(className);
        if (node == null) return false;
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    public boolean isInPool(String className) {
        return nodeMap.containsKey(className);
    }

    public boolean isAppClass(String className) {
        return appClasses.contains(className);
    }

    public boolean isLibraryClass(String className) {
        return !appClasses.contains(className) && !className.equals("java/lang/Object");
    }

    public boolean isUnknownClass(String className) {
        return !nodeMap.containsKey(className) && !className.equals("java/lang/Object");
    }

    public boolean methodOverridesLibrary(String owner, String name, String desc) {
        Set<String> allParents = getAllParents(owner);

        for (String parent : allParents) {
            if (nodeMap.containsKey(parent) && !appClasses.contains(parent)) {
                if (hasMethod(parent, name, desc)) {
                    return true;
                }
            }

            if (!nodeMap.containsKey(parent) && !parent.equals("java/lang/Object")) {
                return true;
            }
        }

        return false;
    }
}
