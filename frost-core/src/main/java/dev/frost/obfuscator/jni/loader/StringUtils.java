package dev.frost.obfuscator.jni.loader;

/**
 * Names of native string helpers emitted in the runtime header.
 */
public final class StringUtils {
    public static final String TO_STD_STRING = "frostjni::StringUtils::toStdString";
    public static final String TO_JSTRING = "frostjni::StringUtils::toJString";

    private StringUtils() {
    }
}


