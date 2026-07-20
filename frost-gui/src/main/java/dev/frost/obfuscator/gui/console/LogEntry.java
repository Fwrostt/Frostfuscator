package dev.frost.obfuscator.gui.console;

import java.time.LocalDateTime;

public record LogEntry(LocalDateTime timestamp, Level level, String transformer, String message, String reference) {
    public enum Level { DEBUG, INFO, WARNING, ERROR, SUCCESS }
}
