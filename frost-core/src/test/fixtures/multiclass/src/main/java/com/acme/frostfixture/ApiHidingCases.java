package com.acme.frostfixture;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class ApiHidingCases {
    private ApiHidingCases() {
    }

    public static String probe() {
        byte[] payload = "frost-api".getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new ByteArrayInputStream(payload)) {
            URI endpoint = URI.create("https://frost.example/protected");
            return endpoint.getHost() + ":" + input.available();
        } catch (IOException exception) {
            throw new IllegalStateException("API fixture failed", exception);
        }
    }
}
