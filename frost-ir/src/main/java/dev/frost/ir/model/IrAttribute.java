package dev.frost.ir.model;

import dev.frost.ir.type.IrType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed, deterministic attribute value domain used by operations and serialization. */
public sealed interface IrAttribute permits IrAttribute.StringValue, IrAttribute.LongValue,
        IrAttribute.DoubleValue, IrAttribute.BooleanValue, IrAttribute.TypeValue,
        IrAttribute.BytesValue, IrAttribute.ArrayValue, IrAttribute.DictionaryValue {

    record StringValue(String value) implements IrAttribute {
        public StringValue { Objects.requireNonNull(value, "value"); }
    }

    record LongValue(long value) implements IrAttribute {}
    /** Raw-bit equality preserves signed zero and distinct class-file NaN payloads. */
    final class DoubleValue implements IrAttribute {
        private final double value;
        public DoubleValue(double value) { this.value = value; }
        public double value() { return value; }
        @Override public boolean equals(Object other) {
            return other instanceof DoubleValue number
                    && Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(number.value);
        }
        @Override public int hashCode() { return Long.hashCode(Double.doubleToRawLongBits(value)); }
        @Override public String toString() { return Double.toString(value); }
    }
    record BooleanValue(boolean value) implements IrAttribute {}

    record TypeValue(IrType value) implements IrAttribute {
        public TypeValue { Objects.requireNonNull(value, "value"); }
    }

    final class BytesValue implements IrAttribute {
        private final byte[] value;
        public BytesValue(byte[] value) { this.value = Objects.requireNonNull(value, "value").clone(); }
        public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof BytesValue bytes && Arrays.equals(value, bytes.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return "bytes[" + value.length + "]"; }
    }

    record ArrayValue(List<IrAttribute> values) implements IrAttribute {
        public ArrayValue { values = List.copyOf(Objects.requireNonNull(values, "values")); }
    }

    record DictionaryValue(Map<String, IrAttribute> values) implements IrAttribute {
        public DictionaryValue { values = Map.copyOf(Objects.requireNonNull(values, "values")); }
    }

    static IrAttribute of(String value) { return new StringValue(value); }
    static IrAttribute of(long value) { return new LongValue(value); }
    static IrAttribute of(double value) { return new DoubleValue(value); }
    static IrAttribute of(boolean value) { return new BooleanValue(value); }
    static IrAttribute of(IrType value) { return new TypeValue(value); }
}
