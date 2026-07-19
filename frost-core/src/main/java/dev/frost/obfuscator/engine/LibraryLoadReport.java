package dev.frost.obfuscator.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LibraryLoadReport {
    private final List<Path> scannedInputs = new ArrayList<>();
    private final List<Path> libraryArchives = new ArrayList<>();
    private final List<LibraryProblem> problems = new ArrayList<>();
    private int loadedClasses;
    private int runtimeClasses;
    private int duplicateClasses;
    private int appShadowedClasses;

    public void scannedInput(Path path) {
        scannedInputs.add(path);
    }

    public void archive(Path path) {
        libraryArchives.add(path);
    }

    public void loadedClass(boolean runtime) {
        loadedClasses++;
        if (runtime) {
            runtimeClasses++;
        }
    }

    public void duplicateClass() {
        duplicateClasses++;
    }

    public void appShadowedClass() {
        appShadowedClasses++;
    }

    public void problem(Path path, String message, Throwable cause) {
        problems.add(new LibraryProblem(path, message, cause == null ? null : cause.getMessage()));
    }

    public List<Path> scannedInputs() {
        return Collections.unmodifiableList(scannedInputs);
    }

    public List<Path> libraryArchives() {
        return Collections.unmodifiableList(libraryArchives);
    }

    public List<LibraryProblem> problems() {
        return Collections.unmodifiableList(problems);
    }

    public int loadedClasses() {
        return loadedClasses;
    }

    public int runtimeClasses() {
        return runtimeClasses;
    }

    public int archiveClasses() {
        return loadedClasses - runtimeClasses;
    }

    public int duplicateClasses() {
        return duplicateClasses;
    }

    public int appShadowedClasses() {
        return appShadowedClasses;
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    public String summary() {
        return loadedClasses + " classes (" + archiveClasses() + " archive, " + runtimeClasses
                + " runtime) from " + libraryArchives.size() + " archives"
                + ", " + duplicateClasses + " duplicate"
                + ", " + appShadowedClasses + " shadowed by app"
                + ", " + problems.size() + " problem" + (problems.size() == 1 ? "" : "s");
    }

    public record LibraryProblem(Path path, String message, String cause) {
    }
}
