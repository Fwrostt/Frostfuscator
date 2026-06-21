package dev.frost.obfuscator.jni.core.selection;

import dev.frost.obfuscator.jni.core.model.ClassModel;
import dev.frost.obfuscator.jni.core.model.MethodModel;

/**
 * Decides which classes and methods should be moved into native code.
 */
public final class NativeSelector {
    private static final String DEFAULT_NATIVE_ANNOTATION = "Ldev/frost/obfuscator/jni/loader/Native;";

    private final NativeSelectionConfig config;

    public NativeSelector(NativeSelectionConfig config) {
        this.config = config;
    }

    public boolean includeClass(ClassModel classModel) {
        if (config.isEmpty()) {
            return true;
        }
        return isPackageIncluded(classModel.internalName())
                || config.includeClasses().contains(classModel.internalName())
                || hasSelectedAnnotation(classModel.annotationDescriptors());
    }

    public boolean includeMethod(ClassModel classModel, MethodModel methodModel) {
        if (!includeClass(classModel) && config.includeMethods().isEmpty()) {
            return false;
        }
        if (config.isEmpty()) {
            return true;
        }
        String methodKey = classModel.internalName() + "#" + methodModel.name();
        return includeClass(classModel)
                || config.includeMethods().contains(methodModel.name())
                || config.includeMethods().contains(methodKey)
                || hasSelectedAnnotation(methodModel.annotationDescriptors());
    }

    private boolean isPackageIncluded(String internalName) {
        for (String packageName : config.includePackages()) {
            if (internalName.startsWith(packageName + "/") || internalName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSelectedAnnotation(Iterable<String> annotationDescriptors) {
        for (String descriptor : annotationDescriptors) {
            if (DEFAULT_NATIVE_ANNOTATION.equals(descriptor) || config.annotationDescriptors().contains(descriptor)) {
                return true;
            }
        }
        return false;
    }
}


