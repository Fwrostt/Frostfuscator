package com.acme.frostfixture;

/**
 * Runtime fixture dedicated to string splitting, Unicode boundaries, repeated
 * literals, and ConstantValue materialization.
 */
public final class StringSplittingCases {
    public static final String FIELD_LITERAL = "split-field-literal";

    private StringSplittingCases() {
    }

    public static String runtimeValue(boolean alternate) {
        String unicode = "split-unicode-\u2744\uFE0F-\uD83D\uDD25-\uD83C\uDF19";
        String repeated = alternate ? "split-repeated-literal" : "split-repeated-literal";
        return unicode + ":" + repeated + ":" + FIELD_LITERAL;
    }
}
