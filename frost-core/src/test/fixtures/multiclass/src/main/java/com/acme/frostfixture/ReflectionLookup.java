package com.acme.frostfixture;

public final class ReflectionLookup {
    private ReflectionLookup() {
    }

    public static String verifyNames() {
        try {
            Class<?> target = Class.forName("com.acme.frostfixture.ReflectionTarget");
            return target.getDeclaredMethod("hiddenMethod").getName();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("reflection fixture failed", exception);
        }
    }
}
