package dev.frost.obfuscator.jni.loader;

/**
 * Runtime descriptor utility names shared with the C++ header.
 */
public final class DescriptorUtils {
    private DescriptorUtils() {
    }

    public static String key(NativeDescriptorLookup lookup) {
        return lookup.ownerInternalName() + "#" + lookup.name() + lookup.descriptor();
    }
}


