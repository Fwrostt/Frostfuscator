package dev.frost.obfuscator.jni.compiler;

/**
 * Captured native compiler process output.
 */
record ProcessResult(int exitCode, String stdout, String stderr) {
}


