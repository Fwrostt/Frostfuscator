package dev.frost.obfuscator.jni.generator.cpp.translator;

import dev.frost.obfuscator.jni.core.model.LabelModel;

import java.util.List;
import java.util.Objects;

/**
 * Native-side exception handler metadata for future precise exception table
 * lowering.
 */
public record ExceptionFrame(LabelModel start, LabelModel end, LabelModel handler, String exceptionInternalName) {
    public ExceptionFrame {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(handler, "handler");
    }

    public static List<ExceptionFrame> empty() {
        return List.of();
    }
}


