package dev.frost.obfuscator.gui.build;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;

public record BuildRecord(LocalDateTime time, Status status, Path output, Duration duration, String message) {
    public enum Status { SUCCESS, WARNING, FAILED, CANCELLED }
}
