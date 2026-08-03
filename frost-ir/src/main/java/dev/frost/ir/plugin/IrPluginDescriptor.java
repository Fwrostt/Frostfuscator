package dev.frost.ir.plugin;

import java.util.Objects;

public record IrPluginDescriptor(String id, String version, int apiVersion) {
    public static final int CURRENT_API_VERSION = 1;

    public IrPluginDescriptor {
        id = requireToken(id, "id");
        version = requireToken(version, "version");
        if (apiVersion < 1) throw new IllegalArgumentException("apiVersion must be positive");
    }

    public IrPluginDescriptor(String id, String version) { this(id, version, CURRENT_API_VERSION); }

    private static String requireToken(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(label + " must be a non-blank token: " + value);
        }
        return value;
    }
}
