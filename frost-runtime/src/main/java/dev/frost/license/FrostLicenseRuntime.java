package dev.frost.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FrostLicenseRuntime {
    private static final String CONFIG_B64 = "FROST_LICENSE_CONFIG_PLACEHOLDER";
    private static final AtomicBoolean VERIFIED = new AtomicBoolean();

    private FrostLicenseRuntime() {
    }

    public static void verify() {
        if (VERIFIED.get()) {
            return;
        }
        synchronized (VERIFIED) {
            if (VERIFIED.get()) {
                return;
            }
            Properties config = loadConfig();
            List<String> problems = new ArrayList<>();
            Properties token = validateToken(config, problems);
            long now = System.currentTimeMillis();

            String product = pick(config, token, "product");
            requireEquals(config, token, "product", product, problems);
            requireEquals(config, token, "license-id", pick(config, token, "license-id"), problems);
            requireEquals(config, token, "customer", pick(config, token, "customer"), problems);
            requireFeatures(config, token, problems);
            checkWindow(config, token, now, problems);
            checkClockRollback(config, now, problems);
            checkHwid(config, token, problems);

            if (!problems.isEmpty()) {
                fail(config, String.join("; ", problems));
            }
            VERIFIED.set(true);
        }
    }

    public static String currentHwid(String salt, String components) {
        return sha256(fingerprintMaterial(components) + "|" + Objects.toString(salt, ""));
    }

    private static Properties loadConfig() {
        Properties properties = new Properties();
        try {
            byte[] bytes = Base64.getDecoder().decode(CONFIG_B64);
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                properties.load(input);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("License configuration is damaged", exception);
        }
        return properties;
    }

    private static Properties validateToken(Properties config, List<String> problems) {
        String token = value(config, "token");
        if (token.isBlank()) {
            return new Properties();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            problems.add("license token has invalid format");
            return new Properties();
        }

        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException exception) {
            problems.add("license token is not valid base64url");
            return new Properties();
        }

        boolean verified = false;
        String publicKey = value(config, "token-public-key");
        String tokenSecret = value(config, "token-secret");
        try {
            if (!publicKey.isBlank()) {
                verified = verifyRsa(payload, signature, publicKey);
            } else if (!tokenSecret.isBlank()) {
                verified = MessageDigest.isEqual(signature, hmac(payload, tokenSecret));
            } else {
                problems.add("license token is configured without a verification key");
            }
        } catch (Exception exception) {
            problems.add("license token signature check failed");
        }
        if (!verified) {
            problems.add("license token signature is invalid");
            return new Properties();
        }

        Properties tokenProperties = new Properties();
        try (InputStream input = new ByteArrayInputStream(payload)) {
            tokenProperties.load(input);
        } catch (IOException exception) {
            problems.add("license token payload is invalid");
        }
        return tokenProperties;
    }

    private static boolean verifyRsa(byte[] payload, byte[] signatureBytes, String publicKeyText) throws Exception {
        String normalized = publicKeyText
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded = Base64.getDecoder().decode(normalized);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(payload);
        return signature.verify(signatureBytes);
    }

    private static void requireEquals(Properties config, Properties token, String key, String expected, List<String> problems) {
        String configured = value(config, key);
        String actual = value(token, key);
        if (!configured.isBlank() && !actual.isBlank() && !configured.equals(actual)) {
            problems.add(key + " mismatch");
        }
    }

    private static void requireFeatures(Properties config, Properties token, List<String> problems) {
        Set<String> required = splitSet(value(config, "required-features"));
        if (required.isEmpty()) {
            return;
        }
        Set<String> licensed = splitSet(value(token, "features"));
        if (!licensed.containsAll(required)) {
            problems.add("license is missing required features");
        }
    }

    private static void checkWindow(Properties config, Properties token, long now, List<String> problems) {
        long notBefore = max(parseLong(value(config, "not-before-epoch")), parseLong(value(token, "not-before-epoch")));
        long expires = minPositive(parseLong(value(config, "expires-at-epoch")), parseLong(value(token, "expires-at-epoch")));
        long graceMillis = Math.max(0L, parseLong(value(config, "grace-millis")));
        if (notBefore > 0L && now < notBefore) {
            problems.add("license is not valid yet");
        }
        if (expires > 0L && now > expires + graceMillis) {
            problems.add("license expired");
        }
    }

    private static void checkClockRollback(Properties config, long now, List<String> problems) {
        if (!Boolean.parseBoolean(value(config, "clock-rollback"))) {
            return;
        }
        String stateKey = value(config, "state-key");
        if (stateKey.isBlank()) {
            return;
        }
        Path statePath = statePath(config);
        long graceMillis = Math.max(0L, parseLong(value(config, "grace-millis")));
        try {
            if (Files.exists(statePath)) {
                String content = Files.readString(statePath, StandardCharsets.UTF_8).trim();
                String[] parts = content.split("\\.");
                if (parts.length != 2 || !MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8), stateMac(parts[0], stateKey).getBytes(StandardCharsets.UTF_8))) {
                    problems.add("license clock state was tampered");
                    return;
                }
                long lastSeen = parseLong(parts[0]);
                if (lastSeen > 0L && now + graceMillis < lastSeen) {
                    problems.add("system clock rollback detected");
                    return;
                }
            }
            Files.createDirectories(statePath.getParent());
            String timestamp = Long.toString(now);
            Files.writeString(statePath, timestamp + "." + stateMac(timestamp, stateKey), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            if (Boolean.parseBoolean(value(config, "state-required"))) {
                problems.add("license state cannot be updated");
            }
        }
    }

    private static Path statePath(Properties config) {
        String configured = value(config, "state-file");
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        String id = value(config, "license-id");
        if (id.isBlank()) {
            id = "default";
        }
        String file = sha256(id).substring(0, 24) + ".state";
        return Path.of(System.getProperty("user.home", "."), ".frost-license", file);
    }

    private static void checkHwid(Properties config, Properties token, List<String> problems) {
        if (!Boolean.parseBoolean(value(config, "hwid-enabled"))) {
            return;
        }
        String components = value(config, "hwid-components");
        String salt = value(config, "hwid-salt");
        String current = currentHwid(salt, components);
        Set<String> allowed = splitSet(value(config, "allowed-hwids"));
        allowed.addAll(splitSet(value(token, "hwid")));
        allowed.addAll(splitSet(value(token, "hwids")));
        if (allowed.isEmpty()) {
            problems.add("license has no HWID binding");
            return;
        }
        boolean matched = allowed.stream().anyMatch(value -> normalizeHwid(value, salt, components).equals(current));
        if (!matched) {
            String problem = "HWID mismatch";
            if (Boolean.parseBoolean(value(config, "print-hwid-on-failure"))) {
                problem += " (current " + current + ")";
            }
            problems.add(problem);
        }
    }

    private static String normalizeHwid(String value, String salt, String components) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("[0-9a-f]{64}")) {
            return normalized;
        }
        return sha256(normalized + "|" + Objects.toString(salt, ""));
    }

    private static String fingerprintMaterial(String componentsText) {
        List<String> components = splitList(componentsText);
        if (components.isEmpty()) {
            components = List.of("mac", "hostname", "os", "user", "machine-id");
        }
        List<String> values = new ArrayList<>();
        for (String component : components) {
            switch (component) {
                case "mac" -> values.add("mac=" + macs());
                case "hostname" -> values.add("host=" + hostname());
                case "os" -> values.add("os=" + System.getProperty("os.name", "") + "|" + System.getProperty("os.arch", ""));
                case "user" -> values.add("user=" + System.getProperty("user.name", ""));
                case "machine-id" -> values.add("machine=" + machineId());
                default -> {
                    if (component.startsWith("env:")) {
                        values.add(component + "=" + System.getenv(component.substring(4)));
                    }
                }
            }
        }
        Collections.sort(values);
        return String.join("|", values);
    }

    private static String macs() {
        List<String> values = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                byte[] mac = networkInterface.getHardwareAddress();
                if (mac == null || mac.length == 0 || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                values.add(HexFormat.of().formatHex(mac));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(values);
        return String.join(",", values);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return Optional.ofNullable(System.getenv("COMPUTERNAME"))
                    .orElseGet(() -> Optional.ofNullable(System.getenv("HOSTNAME")).orElse(""));
        }
    }

    private static String machineId() {
        for (Path path : List.of(Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id"))) {
            try {
                if (Files.isRegularFile(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {
            }
        }
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            try {
                Path machineGuid = Path.of(programData, "Microsoft", "Crypto", "RSA", "MachineKeys");
                if (Files.isDirectory(machineGuid)) {
                    return machineGuid.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static void fail(Properties config, String reason) {
        String message = value(config, "failure-message");
        if (message.isBlank()) {
            message = "License validation failed";
        }
        String full = message + ": " + reason;
        switch (value(config, "failure-action")) {
            case "exit" -> {
                System.err.println(full);
                System.exit(1);
            }
            case "halt" -> {
                System.err.println(full);
                Runtime.getRuntime().halt(1);
            }
            case "warn" -> System.err.println(full);
            default -> throw new IllegalStateException(full);
        }
    }

    private static String stateMac(String value, String key) throws Exception {
        return HexFormat.of().formatHex(hmac(value.getBytes(StandardCharsets.UTF_8), key));
    }

    private static byte[] hmac(byte[] payload, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(payload);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Set<String> splitSet(String value) {
        return new java.util.LinkedHashSet<>(splitList(value));
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,;\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String pick(Properties first, Properties second, String key) {
        String value = value(first, key);
        return value.isBlank() ? value(second, key) : value;
    }

    private static String value(Properties properties, String key) {
        return properties.getProperty(key, "").trim();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long max(long left, long right) {
        return Math.max(left, right);
    }

    private static long minPositive(long left, long right) {
        if (left <= 0L) {
            return right;
        }
        if (right <= 0L) {
            return left;
        }
        return Math.min(left, right);
    }
}
