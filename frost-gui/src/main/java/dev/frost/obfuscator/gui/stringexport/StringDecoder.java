package dev.frost.obfuscator.gui.stringexport;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility for decoding encoded/obfuscated strings and calculating Shannon entropy.
 */
public final class StringDecoder {

    private static final Pattern BASE64_PATTERN = Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$");
    private static final Pattern BASE64_URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+={0,2}$");
    private static final Pattern HEX_PATTERN = Pattern.compile("^(0x|0X)?[0-9a-fA-F]+$");

    private StringDecoder() {}

    public record DecodingResult(String method, String decoded, double confidence) implements Comparable<DecodingResult> {
        @Override public int compareTo(DecodingResult o) { return Double.compare(o.confidence, this.confidence); }
    }

    public static double englishFrequencyDeviation(String input) {
        if (input == null || input.isEmpty()) return 100.0;
        double[] englishFreqs = {
            8.167, 1.492, 2.782, 4.253, 12.702, 2.228, 2.015, 6.094,
            6.966, 0.153, 0.772, 4.025, 2.406, 6.749, 7.507, 1.929,
            0.095, 5.987, 6.327, 9.056, 2.758, 0.978, 2.360, 0.150,
            1.974, 0.074
        };
        int[] counts = new int[26];
        int totalLetters = 0;
        for (char c : input.toLowerCase().toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                counts[c - 'a']++;
                totalLetters++;
            }
        }
        if (totalLetters == 0) return 100.0;
        double dev = 0.0;
        for (int i = 0; i < 26; i++) {
            double expected = englishFreqs[i];
            double actual = (counts[i] * 100.0) / totalLetters;
            dev += Math.abs(expected - actual);
        }
        return dev;
    }

    public static String tryRot13(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append((char) ((c - 'a' + 13) % 26 + 'a'));
            else if (c >= 'A' && c <= 'Z') sb.append((char) ((c - 'A' + 13) % 26 + 'A'));
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String tryXorBruteForce(String input) {
        if (input == null || input.length() > 200) return null;
        String bestResult = null;
        double bestDeviation = 100.0;
        for (int key = 1; key < 256; key++) {
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (char c : input.toCharArray()) {
                char dec = (char) (c ^ key);
                if (dec < 32 && dec != '\n' && dec != '\r' && dec != '\t') { valid = false; break; }
                if (dec > 126 && dec < 160) { valid = false; break; }
                sb.append(dec);
            }
            if (valid) {
                String result = sb.toString();
                double dev = englishFrequencyDeviation(result);
                if (dev < bestDeviation) {
                    bestDeviation = dev;
                    bestResult = result;
                }
            }
        }
        return bestDeviation < 80.0 ? bestResult : null;
    }

    public static String tryReverse(String input) {
        if (input == null) return null;
        return new StringBuilder(input).reverse().toString();
    }

    public static String decodeUnicodeEscapes(String input) {
        if (input == null || !input.contains("\\u")) return input;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (input.startsWith("\\u", i) && i + 5 < input.length()) {
                try {
                    int code = Integer.parseInt(input.substring(i + 2, i + 6), 16);
                    sb.append((char) code);
                    i += 6;
                } catch (NumberFormatException e) {
                    sb.append(input.charAt(i++));
                }
            } else {
                sb.append(input.charAt(i++));
            }
        }
        return sb.toString();
    }

    public static List<DecodingResult> tryAllDecodings(String input) {
        List<DecodingResult> results = new ArrayList<>();
        if (input == null || input.isEmpty()) return results;
        
        String rot13 = tryRot13(input);
        if (rot13 != null && !rot13.equals(input) && englishFrequencyDeviation(rot13) < 80) {
            results.add(new DecodingResult("ROT13", rot13, 100.0 - englishFrequencyDeviation(rot13)));
        }
        
        String xor = tryXorBruteForce(input);
        if (xor != null && !xor.equals(input)) {
            results.add(new DecodingResult("XOR", xor, 100.0 - englishFrequencyDeviation(xor)));
        }
        
        String rev = tryReverse(input);
        if (rev != null && StringCategory.categorize(rev, calculateEntropy(rev), isBase64(rev), isHex(rev)) != StringCategory.GENERAL) {
            results.add(new DecodingResult("Reverse", rev, 80.0));
        }
        
        String uni = decodeUnicodeEscapes(input);
        if (uni != null && !uni.equals(input)) {
            results.add(new DecodingResult("UnicodeEscape", uni, 90.0));
        }

        // Caesar
        for (int shift = 1; shift < 26; shift++) {
            if (shift == 13) continue;
            StringBuilder sb = new StringBuilder();
            for (char c : input.toCharArray()) {
                if (c >= 'a' && c <= 'z') sb.append((char) ((c - 'a' + shift) % 26 + 'a'));
                else if (c >= 'A' && c <= 'Z') sb.append((char) ((c - 'A' + shift) % 26 + 'A'));
                else sb.append(c);
            }
            String c = sb.toString();
            double dev = englishFrequencyDeviation(c);
            if (dev < 50.0) {
                results.add(new DecodingResult("Caesar+" + shift, c, 100.0 - dev));
            }
        }
        
        Collections.sort(results);
        return results;
    }

    public static double calculateEntropy(String input) {
        if (input == null || input.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> charCounts = new HashMap<>();
        for (char c : input.toCharArray()) {
            charCounts.put(c, charCounts.getOrDefault(c, 0) + 1);
        }
        double entropy = 0.0;
        double length = input.length();
        for (int count : charCounts.values()) {
            double freq = count / length;
            entropy -= freq * (Math.log(freq) / Math.log(2));
        }
        return Math.round(entropy * 100.0) / 100.0;
    }

    public static String decode(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        if (input.contains("%")) {
            try {
                String urlDecoded = URLDecoder.decode(input, StandardCharsets.UTF_8);
                if (!urlDecoded.equals(input) && isPrintableAscii(urlDecoded)) {
                    return urlDecoded;
                }
            } catch (Exception ignored) {}
        }

        if (isBase64(input)) {
            try {
                byte[] decodedBytes = Base64.getDecoder().decode(input.trim());
                String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
                if (isPrintableAscii(decoded) && decoded.length() > 2) {
                    return decoded;
                }
            } catch (Exception ignored) {}

            try {
                byte[] decodedBytes = Base64.getUrlDecoder().decode(input.trim());
                String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
                if (isPrintableAscii(decoded) && decoded.length() > 2) {
                    return decoded;
                }
            } catch (Exception ignored) {}
        }

        if (isHex(input)) {
            try {
                String cleanHex = input.startsWith("0x") || input.startsWith("0X") ? input.substring(2) : input;
                if (cleanHex.length() % 2 == 0) {
                    byte[] bytes = new byte[cleanHex.length() / 2];
                    for (int i = 0; i < cleanHex.length(); i += 2) {
                        bytes[i / 2] = (byte) Integer.parseInt(cleanHex.substring(i, i + 2), 16);
                    }
                    String decoded = new String(bytes, StandardCharsets.UTF_8);
                    if (isPrintableAscii(decoded) && decoded.length() > 2) {
                        return decoded;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        List<DecodingResult> res = tryAllDecodings(input);
        if (!res.isEmpty()) {
            return res.get(0).decoded();
        }

        return input;
    }

    public static boolean isBase64(String input) {
        if (input == null || input.length() < 8 || input.length() % 4 != 0) {
            return false;
        }
        return BASE64_PATTERN.matcher(input.trim()).matches() || BASE64_URL_PATTERN.matcher(input.trim()).matches();
    }

    public static boolean isHex(String input) {
        if (input == null || input.length() < 6) {
            return false;
        }
        String clean = input.startsWith("0x") || input.startsWith("0X") ? input.substring(2) : input;
        return clean.length() % 2 == 0 && HEX_PATTERN.matcher(clean).matches();
    }

    public static boolean isLikelyEncoded(String input, double entropy) {
        if (input == null || input.length() < 6) {
            return false;
        }
        if (isBase64(input) || isHex(input) || input.contains("%")) {
            return true;
        }
        return entropy >= 3.8 && input.length() >= 10;
    }

    private static boolean isPrintableAscii(String str) {
        if (str == null || str.isEmpty()) return false;
        for (char c : str.toCharArray()) {
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') {
                return false;
            }
            if (c > 126 && c < 160) {
                return false;
            }
        }
        return true;
    }
}
