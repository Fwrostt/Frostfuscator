package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.model.LabelModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps arbitrary IR label identifiers to compact C++ labels.
 */
public final class CppLabelGenerator implements LabelGenerator {
    private final Map<LabelModel, String> labels = new LinkedHashMap<>();

    @Override
    public String labelName(LabelModel label) {
        return labels.computeIfAbsent(label, ignored -> "label_" + labels.size());
    }
}


