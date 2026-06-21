package dev.frost.obfuscator.jni.core.descriptor;

/**
 * JVM value categories supported by the initial FrostJNI pipeline.
 */
public enum ValueType {
    VOID("V", "void", "v"),
    BOOLEAN("Z", "jboolean", "z"),
    BYTE("B", "jbyte", "b"),
    CHAR("C", "jchar", "c"),
    SHORT("S", "jshort", "s"),
    INT("I", "jint", "i"),
    LONG("J", "jlong", "j"),
    FLOAT("F", "jfloat", "f"),
    DOUBLE("D", "jdouble", "d"),
    ARRAY("[", "jarray", "l"),
    OBJECT("Ljava/lang/Object;", "jobject", "l");

    private final String descriptor;
    private final String jniType;
    private final String jvalueMember;

    ValueType(String descriptor, String jniType, String jvalueMember) {
        this.descriptor = descriptor;
        this.jniType = jniType;
        this.jvalueMember = jvalueMember;
    }

    public String descriptor() {
        return descriptor;
    }

    public String jniType() {
        return jniType;
    }

    public String jvalueMember() {
        return jvalueMember;
    }

    public boolean category2() {
        return this == LONG || this == DOUBLE;
    }
}


