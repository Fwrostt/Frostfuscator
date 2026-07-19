package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.model.LabelModel;

/**
 * Produces stable C++ labels for IR labels.
 */
public interface LabelGenerator {
    String labelName(LabelModel label);
}


