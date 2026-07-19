package dev.frost.obfuscator.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record LibraryOptions(
        List<Path> paths,
        boolean recursive,
        boolean loadRuntime,
        boolean strict
) {
    public LibraryOptions {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
    }
}
