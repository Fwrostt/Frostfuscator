package dev.frost.ir.serialization;

import dev.frost.ir.model.IrMethod;
import dev.frost.ir.snapshot.FrozenMethod;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Deterministic, bounded binary container around the canonical snapshot representation. */
public final class FrozenMethodBinaryCodec {
    private static final int MAGIC = 0x46524952; // FRIR
    private static final short VERSION = 1;
    private static final int HEADER_BYTES = 15;
    private final int maxBytes;
    private final FrozenMethodJsonCodec json;

    public FrozenMethodBinaryCodec() { this(FrozenMethodJsonCodec.DEFAULT_MAX_CHARS); }

    public FrozenMethodBinaryCodec(int maxBytes) {
        if (maxBytes < 1024) throw new IllegalArgumentException("maxBytes must be at least 1024");
        this.maxBytes = maxBytes;
        json = new FrozenMethodJsonCodec(maxBytes);
    }

    public byte[] serialize(IrMethod method) { return encode(json.serialize(method)); }
    public byte[] serialize(FrozenMethod method) { return encode(json.serialize(method)); }

    public FrozenMethod deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_BYTES || bytes.length > maxBytes + HEADER_BYTES) {
            throw new IrSerializationException("Invalid binary snapshot size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IrSerializationException("Invalid binary snapshot magic");
            int version = input.readUnsignedShort();
            if (version != VERSION) throw new IrSerializationException("Unsupported binary snapshot version " + version);
            int compression = input.readUnsignedByte();
            if (compression != 1) throw new IrSerializationException("Unsupported binary snapshot compression " + compression);
            int plainLength = input.readInt(), compressedLength = input.readInt();
            if (plainLength < 0 || plainLength > maxBytes || compressedLength < 0
                    || compressedLength != bytes.length - HEADER_BYTES) {
                throw new IrSerializationException("Invalid binary snapshot lengths");
            }
            byte[] compressed = input.readNBytes(compressedLength);
            return json.deserialize(new String(inflate(compressed, plainLength), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IrSerializationException("Malformed binary snapshot", exception);
        }
    }

    private byte[] encode(String value) {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        if (plain.length > maxBytes) throw new IrSerializationException("Binary snapshot exceeds configured size limit");
        byte[] compressed = deflate(plain);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(HEADER_BYTES + compressed.length);
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(MAGIC);
                output.writeShort(VERSION);
                output.writeByte(1);
                output.writeInt(plain.length);
                output.writeInt(compressed.length);
                output.write(compressed);
            }
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory binary encoding failed", impossible);
        }
    }

    private byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(input.length / 2);
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk));
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private byte[] inflate(byte[] input, int expectedLength) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input);
            byte[] output = new byte[expectedLength];
            int offset = 0;
            while (!inflater.finished() && offset < output.length) {
                int count = inflater.inflate(output, offset, output.length - offset);
                if (count == 0 && inflater.needsInput()) break;
                offset += count;
            }
            if (!inflater.finished() || offset != expectedLength || inflater.getRemaining() != 0) {
                throw new IrSerializationException("Binary snapshot decompression length mismatch");
            }
            return output;
        } catch (DataFormatException exception) {
            throw new IrSerializationException("Corrupt binary snapshot payload", exception);
        } finally {
            inflater.end();
        }
    }
}
