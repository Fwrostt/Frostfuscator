package dev.frost.obfuscator.gui.stringexport;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Categorizes strings into predefined analysis categories.
 */
public enum StringCategory {
    URL,
    IP_ADDRESS,
    FILE_PATH,
    CLASS_NAME,
    REFLECTION_TARGET,
    SQL,
    REGEX,
    BASE64,
    HEX,
    HIGH_ENTROPY,
    ERROR_MESSAGE,
    RESOURCE_PATH,
    CRYPTO_KEY,
    API_KEY,
    JWT,
    UUID,
    EMAIL,
    PHONE_NUMBER,
    VERSION_STRING,
    COMMAND,
    NATIVE_LIBRARY,
    SERIALIZATION,
    OBFUSCATED,
    ENCRYPTED,
    STACK_TRACE,
    LOG_MESSAGE,
    NETWORK,
    REGISTRY,
    ENVIRONMENT_VAR,
    FORMAT_STRING,
    XML_HTML,
    JSON_FRAGMENT,
    ANDROID,
    PERMISSION,
    GENERAL;

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)^(https?|ftp|ws|wss|jdbc|file)://.*");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b.*");
    private static final Pattern SQL_PATTERN = Pattern.compile("(?i).*\\b(SELECT|INSERT INTO|UPDATE|DELETE FROM|CREATE TABLE|DROP TABLE|WHERE|GROUP BY|ORDER BY)\\b.*");
    private static final Pattern REFLECTION_PATTERN = Pattern.compile("^(forName|getDeclaredMethod|getMethod|getDeclaredField|getField|newInstance|invoke|setAccessible|findStatic|findVirtual|findSpecial)$");
    private static final Pattern REGEX_PATTERN = Pattern.compile(".*(\\\\d|\\\\w|\\\\s|\\^|\\$|\\[.*?\\]|\\(\\?|\\{\\d+,?\\d*\\}).*");
    private static final Pattern UUID_PATTERN = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$");
    private static final Pattern JWT_PATTERN = Pattern.compile("^eyJ[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]*$");
    
    public static StringCategory categorize(String input, double entropy, boolean isBase64, boolean isHex) {
        if (input == null || input.isBlank()) {
            return GENERAL;
        }

        String str = input.trim();
        String lower = str.toLowerCase(Locale.ROOT);

        if (URL_PATTERN.matcher(str).matches()) {
            return URL;
        }

        if (IPV4_PATTERN.matcher(str).matches()) {
            return IP_ADDRESS;
        }

        if (JWT_PATTERN.matcher(str).matches()) {
            return JWT;
        }
        
        if (str.startsWith("sk-") || str.startsWith("pk-") || str.startsWith("api_") || str.startsWith("AKIA") || str.startsWith("ghp_") || str.startsWith("gho_") || str.startsWith("AIza")) {
            return API_KEY;
        }

        if (UUID_PATTERN.matcher(str).matches()) {
            return UUID;
        }

        if (EMAIL_PATTERN.matcher(str).matches()) {
            return EMAIL;
        }

        if (lower.contains("/bin/sh") || lower.contains("/bin/bash") || lower.contains("cmd.exe") || lower.contains("powershell") || lower.contains("runtime.exec") || lower.contains("processbuilder")) {
            return COMMAND;
        }

        if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")) {
            return NATIVE_LIBRARY;
        }

        if (lower.contains("log.") || lower.contains("logger") || str.contains("WARN") || str.contains("INFO") || str.contains("DEBUG") || str.contains("[INFO]") || str.contains("[ERROR]") || str.contains("[WARN]") || str.contains("[DEBUG]")) {
            return LOG_MESSAGE;
        }

        if (str.contains("%s") || str.contains("%d") || str.contains("%f") || str.contains("%n") || str.contains("{0}") || str.contains("{1}")) {
            return FORMAT_STRING;
        }

        if ((str.startsWith("<") && str.endsWith(">")) || (str.contains("&amp;") || str.contains("&lt;"))) {
            return XML_HTML;
        }

        if ((str.startsWith("{") || str.startsWith("[")) && str.contains(":")) {
            return JSON_FRAGMENT;
        }

        if (str.startsWith("content://") || str.startsWith("intent://") || str.contains("android.permission.")) {
            return ANDROID;
        }

        if (str.contains("java.security.") || str.contains("javax.crypto.") || str.contains("android.permission.")) {
            return PERMISSION;
        }

        if (REFLECTION_PATTERN.matcher(str).matches() || lower.contains("getdeclaredmethod") || lower.contains("getdeclaredfield")) {
            return REFLECTION_TARGET;
        }

        if (SQL_PATTERN.matcher(str).matches()) {
            return SQL;
        }

        if (str.startsWith("META-INF/") || str.startsWith("assets/") || str.startsWith("static/") || str.startsWith("config/")) {
            return RESOURCE_PATH;
        }

        if (lower.endsWith(".properties") || lower.endsWith(".xml") || lower.endsWith(".json") || lower.endsWith(".yml") || lower.endsWith(".txt") || (str.contains("/") || str.contains("\\")) && !str.contains(" ")) {
            return FILE_PATH;
        }

        if ((str.startsWith("L") && str.endsWith(";")) || (str.contains("/") && !str.contains(" ")) || (str.contains(".") && Character.isUpperCase(str.charAt(str.lastIndexOf('.') + 1)))) {
            return CLASS_NAME;
        }

        if (REGEX_PATTERN.matcher(str).matches()) {
            return REGEX;
        }
        
        if (str.contains("AES") || str.contains("DES") || str.contains("RSA") || str.contains("Blowfish") || (isBase64 && (str.length() == 16 || str.length() == 24 || str.length() == 32 || str.length() == 44 || str.length() == 64))) {
            return CRYPTO_KEY;
        }

        if (isBase64 && str.length() >= 8) {
            return BASE64;
        }

        if (isHex && str.length() >= 6) {
            return HEX;
        }

        if (lower.contains("error") || lower.contains("exception") || lower.contains("fail") || lower.contains("invalid") || lower.contains("cannot")) {
            return ERROR_MESSAGE;
        }
        
        if (str.length() <= 3 && !str.isBlank() && str.chars().allMatch(c -> c == str.charAt(0)) || str.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) {
            return OBFUSCATED;
        }

        if (entropy >= 4.0) {
            return HIGH_ENTROPY;
        }

        return GENERAL;
    }
}
