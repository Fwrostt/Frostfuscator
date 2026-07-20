package com.acme.frostfixture;

public final class MbaCases {
    private MbaCases() {
    }

    public static long mix(int left, long right) {
        int sum = left + 0x13579BDF;
        int difference = sum - left;
        int booleanMix = (sum & difference) ^ (sum | difference);
        long wide = (right + booleanMix) - (~right);
        return (wide ^ right) | (wide & -right);
    }
}
