package dev.frost.obfuscator.jni.loader;

import java.util.Objects;

/**
 * Descriptor lookup key used by generators and caches.
 */
public record NativeDescriptorLookup(String ownerInternalName, String name, String descriptor) {
    public NativeDescriptorLookup {
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}


