package dev.frost.api.remapper;

/**
 * Interface allowing plugins to supply custom obfuscated symbol name generators (dictionary, invisible, random).
 */
public interface NameGeneratorPlugin {

    /**
     * @return unique ID of name generator
     */
    String id();

    /**
     * Generates an obfuscated class name.
     */
    String generateClassName(String originalInternalName, int index);

    /**
     * Generates an obfuscated field name.
     */
    String generateFieldName(String className, String originalFieldName, String descriptor, int index);

    /**
     * Generates an obfuscated method name.
     */
    String generateMethodName(String className, String originalMethodName, String descriptor, int index);
}
