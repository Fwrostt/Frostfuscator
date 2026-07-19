package com.acme.frostfixture;

import java.util.function.Function;

public final class LambdaUser {
    private LambdaUser() {
    }

    public static String join(String label, int number) {
        Function<Integer, String> render = value -> label + "-" + (value + 9);
        return render.apply(number);
    }
}
