package com.acme.frostfixture;

public final class NativeBridge {
    public native int nativeValue(int input);

    public int safeValue(int input) {
        return input + 1;
    }
}
