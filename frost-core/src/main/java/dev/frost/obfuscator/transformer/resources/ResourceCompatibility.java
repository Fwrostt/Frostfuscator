package dev.frost.obfuscator.transformer.resources;

import dev.frost.obfuscator.transformer.Context;

import java.util.HashSet;
import java.util.Set;

/** Keeps resources owned by transformation-excluded libraries at their runtime-visible paths. */
final class ResourceCompatibility {
    private ResourceCompatibility() {
    }

    static Set<String> preservedPackages(Context context) {
        Set<String> packages = new HashSet<>();
        for (String className : context.pool().getTransformationExclusions().keySet()) {
            int separator = className.lastIndexOf('/');
            if (separator > 0) packages.add(className.substring(0, separator));
        }
        return packages;
    }

    static boolean isPreservedLibraryResource(String resourceName, Set<String> preservedPackages) {
        int separator = resourceName.lastIndexOf('/');
        if (separator <= 0) return false;

        String resourcePackage = resourceName.substring(0, separator);
        for (String classPackage : preservedPackages) {
            if (resourcePackage.equals(classPackage)
                    || resourcePackage.startsWith(classPackage + "/")
                    || classPackage.startsWith(resourcePackage + "/")) {
                return true;
            }
        }
        return false;
    }
}
