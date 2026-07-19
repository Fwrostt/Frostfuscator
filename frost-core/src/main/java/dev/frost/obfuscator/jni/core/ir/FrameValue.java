package dev.frost.obfuscator.jni.core.ir;

import dev.frost.obfuscator.jni.core.descriptor.ValueType;

import java.util.Objects;

/**
 * Typed value tracked by future stack/local simulations.
 */
public record FrameValue(ValueType valueType, StackSlot slot) {
    public FrameValue {
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(slot, "slot");
    }

    public static FrameValue of(ValueType valueType) {
        return new FrameValue(valueType, valueType.category2() ? StackSlot.CATEGORY_2 : StackSlot.CATEGORY_1);
    }
}


