package dev.frost.obfuscator.jni.core.model;

/**
 * Parsed try/catch range. Exceptions are modeled now but not lowered by the
 * initial C++ generator.
 */
public record TryCatchModel(LabelModel start, LabelModel end, LabelModel handler, String exceptionInternalName) {
}


