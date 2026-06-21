package dev.frost.obfuscator.jni.loader;

/**
 * Names of native string conversion helpers emitted in the runtime header.
 */
public final class NativeStringConversion {
    public static final String TO_STD_STRING = "frostjni::toStdString";
    public static final String TO_JSTRING = "frostjni::toJString";

    private NativeStringConversion() {
    }
}


