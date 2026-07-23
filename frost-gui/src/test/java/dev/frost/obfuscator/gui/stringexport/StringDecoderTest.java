package dev.frost.obfuscator.gui.stringexport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StringDecoderTest {

    @Test
    void calculatesShannonEntropy() {
        assertEquals(0.0, StringDecoder.calculateEntropy(""));
        assertEquals(0.0, StringDecoder.calculateEntropy("AAAAA"));
        assertTrue(StringDecoder.calculateEntropy("abcdefghijklmnopqrstuvwxyz") > 4.5);
    }

    @Test
    void decodesBase64Strings() {
        String base64 = "SGVsbG8gRnJvc3RmdXNjYXRvcg=="; // "Hello Frostfuscator"
        assertTrue(StringDecoder.isBase64(base64));
        assertEquals("Hello Frostfuscator", StringDecoder.decode(base64));
    }

    @Test
    void decodesHexStringSequences() {
        String hex = "48656c6c6f20576f726c64"; // "Hello World"
        assertTrue(StringDecoder.isHex(hex));
        assertEquals("Hello World", StringDecoder.decode(hex));
    }

    @Test
    void decodesUrlEncodedStrings() {
        String urlEncoded = "Hello%20Frost%20World";
        assertEquals("Hello Frost World", StringDecoder.decode(urlEncoded));
    }

    @Test
    void detectsLikelyEncodedStrings() {
        assertTrue(StringDecoder.isLikelyEncoded("SGVsbG8gRnJvc3RmdXNjYXRvcg==", 3.5));
        assertTrue(StringDecoder.isLikelyEncoded("48656c6c6f20576f726c64", 3.0));
        assertTrue(StringDecoder.isLikelyEncoded("aX8zK9mQpL2vXmN7", 4.0));
        assertFalse(StringDecoder.isLikelyEncoded("Hello", 1.5));
    }
}
