package dev.frost.ir.serialization;

public final class IrSerializationException extends IllegalArgumentException {
    public IrSerializationException(String message) { super(message); }
    public IrSerializationException(String message, Throwable cause) { super(message, cause); }
}
