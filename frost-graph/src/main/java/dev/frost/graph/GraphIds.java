package dev.frost.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Deterministic identifiers that never expose untrusted labels to renderer syntax. */
public final class GraphIds {
    private GraphIds() {
    }

    public static String graphId(GraphType type, String scope) {
        return type.name().toLowerCase() + "-" + hash(scope, 16);
    }

    public static String nodeId(String namespace, String semanticKey) {
        return namespace + "-" + hash(semanticKey, 24);
    }

    public static String rendererId(String semanticId) {
        return "n_" + hash(semanticId, 20);
    }

    public static String edgeId(String source, String target, EdgeType type, String label) {
        return "e_" + hash(source + "\u0000" + target + "\u0000" + type + "\u0000" + label, 24);
    }

    public static String hash(String value, int characters) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) hex.append(String.format("%02x", part));
            return hex.substring(0, Math.min(Math.max(1, characters), hex.length()));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String hex(byte[] value) {
        StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte part : value) hex.append(String.format("%02x", part));
        return hex.toString();
    }
}
