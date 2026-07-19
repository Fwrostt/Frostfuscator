package com.acme.frostfixture;

public final class FlowCases {
    private FlowCases() {
    }

    public static int score(int seed, String value) {
        int result = 0;
        for (int i = 0; i < 6; i++) {
            if (((seed + i) & 1) == 0) {
                result += branch(seed, i);
            } else {
                result -= branch(i, seed);
            }
        }
        switch (Math.floorMod(value.length() + seed, 5)) {
            case 0 -> result += 11;
            case 1 -> result += 17;
            case 2 -> result += 23;
            case 3 -> result += 29;
            default -> result += 31;
        }
        try {
            if (result == Integer.MIN_VALUE) {
                throw new IllegalStateException("fixture impossible path");
            }
        } catch (RuntimeException exception) {
            result ^= exception.getMessage().length();
        }
        return result;
    }

    private static int branch(int left, int right) {
        return (left + 3) * (right + 5);
    }
}
