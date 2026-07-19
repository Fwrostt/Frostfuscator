package dev.frost.obfuscator.jni.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Switch instruction operands independent of ASM switch nodes.
 */
public record SwitchModel(LabelModel defaultLabel, List<Integer> keys, List<LabelModel> labels) {
    public SwitchModel {
        Objects.requireNonNull(defaultLabel, "defaultLabel");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        if (keys.size() != labels.size()) {
            throw new IllegalArgumentException("Switch keys and labels must have the same size.");
        }
    }
}


