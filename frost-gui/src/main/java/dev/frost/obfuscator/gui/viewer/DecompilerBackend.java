package dev.frost.obfuscator.gui.viewer;

import java.nio.file.Path;

/**
 * A pluggable source backend. Additional decompilers only need to implement
 * this contract; the viewer, cache, cancellation, and UI remain unchanged.
 */
public interface DecompilerBackend {
    String id();
    String displayName();
    String version();
    DecompileResult decompile(Path archive, String classEntry) throws Exception;
}
