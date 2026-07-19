package com.acme.frostfixture;

public final class ServiceImpl implements ServiceApi {
    private int privateCounter = 3;

    @Override
    public String compute(String input, int value) {
        privateCounter += value;
        return input + ":" + privateCounter + ":" + helper(input);
    }

    private static String helper(String input) {
        return new StringBuilder(input).reverse().toString();
    }
}
