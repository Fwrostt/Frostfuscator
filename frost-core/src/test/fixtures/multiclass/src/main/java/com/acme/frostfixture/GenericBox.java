package com.acme.frostfixture;

import java.util.ArrayList;
import java.util.List;

public final class GenericBox<T extends Comparable<T>> {
    private final List<T> values = new ArrayList<>();

    public void add(T value) {
        values.add(value);
    }

    public T first() {
        return values.isEmpty() ? null : values.get(0);
    }
}
