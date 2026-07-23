package dev.frost.api.encryption;

import org.objectweb.asm.tree.MethodNode;

/**
 * Interface allowing plugins to supply custom string encryption algorithms and decryptor method generators.
 */
public interface StringEncryptorPlugin {

    /**
     * @return unique ID of string encryptor
     */
    String id();

    /**
     * Encrypts plaintext string using a key or seed.
     */
    String encrypt(String plainText, String key);

    /**
     * Generates an inlined synthetic ASM MethodNode that decrypts strings at runtime.
     *
     * @param methodName generated method name
     * @param key encryption key
     * @return ASM MethodNode representing (String encrypted) -> String decrypted
     */
    MethodNode generateDecryptorMethod(String methodName, String key);
}
