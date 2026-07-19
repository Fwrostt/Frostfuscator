package com.acme.frostfixture;

public final class StringsAndNumbers {
    public static final String PUBLIC_CONSTANT = "fixture-public-constant";
    private static final String SECRET = "fixture-secret-string";

    private StringsAndNumbers() {
    }

    public static String message(int seed) {
        return SECRET + ":" + numericMixer(seed) + ":" + PUBLIC_CONSTANT;
    }

    public static int numericMixer(int value) {
        int mixed = value * 31 + 0x5f3759df;
        mixed ^= 123456789;
        mixed += 987654321;
        return mixed;
    }
}
