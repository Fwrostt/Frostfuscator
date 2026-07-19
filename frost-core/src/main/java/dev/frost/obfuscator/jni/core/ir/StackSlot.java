package dev.frost.obfuscator.jni.core.ir;

/**
 * JVM stack slot width.
 */
public enum StackSlot {
    CATEGORY_1(1),
    CATEGORY_2(2);

    private final int width;

    StackSlot(int width) {
        this.width = width;
    }

    public int width() {
        return width;
    }
}


