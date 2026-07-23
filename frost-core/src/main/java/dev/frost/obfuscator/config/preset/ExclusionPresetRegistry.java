package dev.frost.obfuscator.config.preset;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public class ExclusionPresetRegistry {

    private final Set<ExclusionPreset> activePresets = new LinkedHashSet<>();

    public ExclusionPresetRegistry() {
    }

    public ExclusionPresetRegistry(Collection<String> presetNames) {
        addPresets(presetNames);
    }

    public void addPreset(ExclusionPreset preset) {
        if (preset != null) {
            activePresets.add(preset);
        }
    }

    public void addPresets(Collection<String> presetNames) {
        if (presetNames == null) return;
        for (String name : presetNames) {
            ExclusionPreset preset = ExclusionPreset.parse(name);
            if (preset != null) {
                activePresets.add(preset);
            }
        }
    }

    public Set<ExclusionPreset> getActivePresets() {
        return Collections.unmodifiableSet(activePresets);
    }

    public List<String> getCombinedPackageExclusions() {
        List<String> result = new ArrayList<>();
        for (ExclusionPreset preset : activePresets) {
            result.addAll(preset.getPackageExclusions());
        }
        return result;
    }

    public boolean isClassExcludedByPreset(ClassNode classNode) {
        if (classNode == null || activePresets.isEmpty()) return false;

        for (ExclusionPreset preset : activePresets) {
            // Check interfaces implemented by class
            if (classNode.interfaces != null && !preset.getInterfaceExclusions().isEmpty()) {
                for (String iface : classNode.interfaces) {
                    if (preset.getInterfaceExclusions().contains(iface)) {
                        return true;
                    }
                }
            }

            // Check class annotations
            if (!preset.getAnnotationExclusions().isEmpty()) {
                if (hasAnyAnnotation(classNode.visibleAnnotations, preset.getAnnotationExclusions()) ||
                    hasAnyAnnotation(classNode.invisibleAnnotations, preset.getAnnotationExclusions())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isMethodExcludedByPreset(MethodNode methodNode) {
        if (methodNode == null || activePresets.isEmpty()) return false;
        for (ExclusionPreset preset : activePresets) {
            if (hasAnyAnnotation(methodNode.visibleAnnotations, preset.getAnnotationExclusions()) ||
                hasAnyAnnotation(methodNode.invisibleAnnotations, preset.getAnnotationExclusions())) {
                return true;
            }
        }
        return false;
    }

    public boolean isFieldExcludedByPreset(FieldNode fieldNode) {
        if (fieldNode == null || activePresets.isEmpty()) return false;
        for (ExclusionPreset preset : activePresets) {
            if (hasAnyAnnotation(fieldNode.visibleAnnotations, preset.getAnnotationExclusions()) ||
                hasAnyAnnotation(fieldNode.invisibleAnnotations, preset.getAnnotationExclusions())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyAnnotation(List<AnnotationNode> annotations, List<String> targetDescriptors) {
        if (annotations == null || annotations.isEmpty()) return false;
        for (AnnotationNode ann : annotations) {
            if (ann.desc != null && targetDescriptors.contains(ann.desc)) {
                return true;
            }
        }
        return false;
    }
}
