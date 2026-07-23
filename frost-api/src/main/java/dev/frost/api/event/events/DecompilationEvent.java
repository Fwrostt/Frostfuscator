package dev.frost.api.event.events;

/**
 * Fired when a class file is decompiled in the GUI or CLI.
 */
public final class DecompilationEvent {

    private final String decompilerName;
    private final String className;
    private String decompiledSource;

    public DecompilationEvent(String decompilerName, String className, String decompiledSource) {
        this.decompilerName = decompilerName;
        this.className = className;
        this.decompiledSource = decompiledSource;
    }

    public String decompilerName() {
        return decompilerName;
    }

    public String className() {
        return className;
    }

    public String decompiledSource() {
        return decompiledSource;
    }

    public void setDecompiledSource(String decompiledSource) {
        this.decompiledSource = decompiledSource;
    }
}
