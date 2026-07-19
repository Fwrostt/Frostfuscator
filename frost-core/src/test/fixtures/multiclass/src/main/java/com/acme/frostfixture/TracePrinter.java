package com.acme.frostfixture;

public final class TracePrinter {
    private TracePrinter() {
    }

    public static void capture(Throwable throwable) {
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }
}
