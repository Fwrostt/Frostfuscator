package dev.frost.api.decompiler;

/**
 * Interface allowing plugins to register custom third-party decompiler engines into Frostfuscator.
 */
public interface CustomDecompilerProvider {

    /**
     * @return unique ID of decompiler (e.g. "my-plugin:jadx")
     */
    String id();

    /**
     * @return display name shown in GUI decompiler dropdown
     */
    String name();

    /**
     * @return version string
     */
    String version();

    /**
     * Decompiles class bytecode into Java source text.
     *
     * @param className internal class name ("com/example/MyClass")
     * @param classBytes raw .class bytes
     * @return decompiled Java source code string
     * @throws Exception if decompilation fails
     */
    String decompile(String className, byte[] classBytes) throws Exception;
}
