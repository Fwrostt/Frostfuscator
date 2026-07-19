package com.acme.frostfixture;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ResourceUser {
    private ResourceUser() {
    }

    public static String readConfigMarker() {
        try (InputStream input = ResourceUser.class.getClassLoader().getResourceAsStream("fixture-config.yml")) {
            if (input == null) {
                return "missing";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
            return exception.getClass().getSimpleName();
        }
    }
}
