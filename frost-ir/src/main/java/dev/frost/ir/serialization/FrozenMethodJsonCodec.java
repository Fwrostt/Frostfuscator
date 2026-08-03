package dev.frost.ir.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frost.ir.core.IrId;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.snapshot.FrozenMethod;
import dev.frost.ir.snapshot.IrFreezer;
import dev.frost.ir.type.ArrayType;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.type.UninitializedType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical, versioned, checksummed JSON codec for identity-free Frost-IR snapshots. */
public final class FrozenMethodJsonCodec {
    public static final String FORMAT = "frost-ir.snapshot";
    public static final int VERSION = 1;
    public static final int DEFAULT_MAX_CHARS = 64 * 1024 * 1024;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final int maxChars;

    public FrozenMethodJsonCodec() { this(DEFAULT_MAX_CHARS); }

    public FrozenMethodJsonCodec(int maxChars) {
        if (maxChars < 1024) throw new IllegalArgumentException("maxChars must be at least 1024");
        this.maxChars = maxChars;
    }

    public String serialize(IrMethod method) { return serialize(new IrFreezer().freeze(method)); }

    public String serialize(FrozenMethod method) {
        Objects.requireNonNull(method, "method");
        JsonObject payload = writeMethod(method);
        String canonicalPayload = gson.toJson(payload);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("format", FORMAT);
        envelope.addProperty("version", VERSION);
        envelope.addProperty("sha256", sha256(canonicalPayload));
        envelope.add("method", payload);
        String output = gson.toJson(envelope);
        if (output.length() > maxChars) throw new IrSerializationException("Serialized snapshot exceeds configured size limit");
        return output;
    }

    public FrozenMethod deserialize(String json) {
        Objects.requireNonNull(json, "json");
        if (json.length() > maxChars) throw new IrSerializationException("Serialized snapshot exceeds configured size limit");
        try {
            JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
            if (!FORMAT.equals(string(envelope, "format"))) throw new IrSerializationException("Unsupported IR format");
            int version = integer(envelope, "version");
            if (version != VERSION) throw new IrSerializationException("Unsupported IR version " + version);
            JsonObject payload = object(envelope, "method");
            String actual = sha256(gson.toJson(payload));
            if (!actual.equals(string(envelope, "sha256"))) throw new IrSerializationException("Snapshot checksum mismatch");
            return readMethod(payload);
        } catch (IrSerializationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IrSerializationException("Malformed Frost-IR snapshot", exception);
        }
    }

    private JsonObject writeMethod(FrozenMethod method) {
        JsonObject out = new JsonObject();
        out.add("signature", writeSignature(method.signature()));
        out.addProperty("sourceRevision", Long.toUnsignedString(method.sourceRevision()));
        out.add("entryBlock", nullableId(method.entryBlock()));
        out.add("parameters", array(method.parameters(), this::writeParameter));
        out.add("blocks", array(method.blocks(), this::writeBlock));
        out.add("edges", array(method.edges(), this::writeEdge));
        out.add("exceptionRegions", array(method.exceptionRegions(), this::writeRegion));
        out.add("values", array(method.values(), this::writeValue));
        out.add("metadata", writeStrings(method.metadata()));
        return out;
    }

    private FrozenMethod readMethod(JsonObject in) {
        return new FrozenMethod(readSignature(object(in, "signature")), unsignedLong(in, "sourceRevision"),
                nullableId(in.get("entryBlock")), readArray(in, "parameters", this::readParameter),
                readArray(in, "blocks", this::readBlock), readArray(in, "edges", this::readEdge),
                readArray(in, "exceptionRegions", this::readRegion), readArray(in, "values", this::readValue),
                readStrings(object(in, "metadata")));
    }

    private JsonObject writeSignature(MethodSignature signature) {
        JsonObject out = new JsonObject();
        out.addProperty("owner", signature.owner());
        out.addProperty("name", signature.name());
        out.add("type", writeType(signature.type()));
        out.addProperty("access", signature.access());
        out.add("genericSignature", nullableString(signature.genericSignature()));
        out.add("declaredExceptions", strings(signature.declaredExceptions()));
        return out;
    }

    private MethodSignature readSignature(JsonObject in) {
        return new MethodSignature(string(in, "owner"), string(in, "name"),
                (MethodType) readType(object(in, "type")), integer(in, "access"),
                nullableString(in.get("genericSignature")), readStringArray(in, "declaredExceptions"));
    }

    private JsonObject writeParameter(FrozenMethod.Parameter value) {
        JsonObject out = idObject(value.id());
        out.addProperty("index", value.index()); out.addProperty("name", value.name()); out.add("value", id(value.value()));
        return out;
    }
    private FrozenMethod.Parameter readParameter(JsonObject in) {
        return new FrozenMethod.Parameter(readId(in), integer(in, "index"), string(in, "name"), id(in, "value"));
    }

    private JsonObject writeBlock(FrozenMethod.Block value) {
        JsonObject out = idObject(value.id()); out.addProperty("name", value.name());
        out.add("phis", array(value.phis(), this::writePhi));
        out.add("instructions", array(value.instructions(), this::writeInstruction));
        out.add("metadata", writeStrings(value.metadata())); return out;
    }
    private FrozenMethod.Block readBlock(JsonObject in) {
        return new FrozenMethod.Block(readId(in), string(in, "name"), readArray(in, "phis", this::readPhi),
                readArray(in, "instructions", this::readInstruction), readStrings(object(in, "metadata")));
    }

    private JsonObject writePhi(FrozenMethod.Phi value) {
        JsonObject out = idObject(value.id()); out.add("result", id(value.result()));
        out.add("inputs", array(value.inputs(), input -> {
            JsonObject item = new JsonObject(); item.add("edge", id(input.edge())); item.add("value", id(input.value())); return item;
        }));
        out.add("metadata", writeStrings(value.metadata())); return out;
    }
    private FrozenMethod.Phi readPhi(JsonObject in) {
        return new FrozenMethod.Phi(readId(in), id(in, "result"), readArray(in, "inputs", item ->
                new FrozenMethod.PhiInput(id(item, "edge"), id(item, "value"))), readStrings(object(in, "metadata")));
    }

    private JsonObject writeInstruction(FrozenMethod.Instruction value) {
        JsonObject out = idObject(value.id()); out.add("operation", writeOperation(value.operation()));
        out.add("operands", ids(value.operands())); out.add("results", ids(value.results()));
        out.add("metadata", writeStrings(value.metadata())); return out;
    }
    private FrozenMethod.Instruction readInstruction(JsonObject in) {
        return new FrozenMethod.Instruction(readId(in), readOperation(object(in, "operation")),
                readIds(in, "operands"), readIds(in, "results"), readStrings(object(in, "metadata")));
    }

    private JsonObject writeEdge(FrozenMethod.Edge value) {
        JsonObject out = idObject(value.id()); out.add("source", id(value.source())); out.add("target", id(value.target()));
        out.addProperty("kind", value.kind().name()); out.addProperty("label", value.label());
        out.add("catchType", value.catchType() == null ? JsonNull.INSTANCE : writeType(value.catchType()));
        out.addProperty("priority", value.priority()); out.add("values", array(value.values(), edgeValue -> {
            JsonObject item = idObject(edgeValue.id()); item.add("result", id(edgeValue.result()));
            item.addProperty("role", edgeValue.role()); item.add("metadata", writeStrings(edgeValue.metadata())); return item;
        }));
        out.add("metadata", writeStrings(value.metadata())); return out;
    }
    private FrozenMethod.Edge readEdge(JsonObject in) {
        JsonElement catchType = in.get("catchType");
        return new FrozenMethod.Edge(readId(in), id(in, "source"), id(in, "target"),
                EdgeKind.valueOf(string(in, "kind")), string(in, "label"),
                catchType == null || catchType.isJsonNull() ? null : (ReferenceType) readType(catchType.getAsJsonObject()),
                integer(in, "priority"), readArray(in, "values", item -> new FrozenMethod.EdgeValueView(
                readId(item), id(item, "result"), string(item, "role"), readStrings(object(item, "metadata")))),
                readStrings(object(in, "metadata")));
    }

    private JsonObject writeRegion(FrozenMethod.ExceptionRegionView value) {
        JsonObject out = idObject(value.id()); out.add("protectedBlocks", ids(value.protectedBlocks()));
        out.add("handler", id(value.handler()));
        out.add("catchType", value.catchType() == null ? JsonNull.INSTANCE : writeType(value.catchType()));
        out.addProperty("priority", value.priority()); out.add("metadata", writeStrings(value.metadata())); return out;
    }
    private FrozenMethod.ExceptionRegionView readRegion(JsonObject in) {
        JsonElement catchType = in.get("catchType");
        return new FrozenMethod.ExceptionRegionView(readId(in), readIds(in, "protectedBlocks"), id(in, "handler"),
                catchType == null || catchType.isJsonNull() ? null : (ReferenceType) readType(catchType.getAsJsonObject()),
                integer(in, "priority"), readStrings(object(in, "metadata")));
    }

    private JsonObject writeValue(FrozenMethod.ValueView value) {
        JsonObject out = idObject(value.id()); out.add("type", writeType(value.type())); out.add("definition", id(value.definition()));
        out.addProperty("resultIndex", value.resultIndex()); out.add("debugName", nullableString(value.debugName()));
        out.add("uses", array(value.uses(), use -> {
            JsonObject item = new JsonObject(); item.add("user", id(use.user())); item.addProperty("index", use.index()); return item;
        }));
        out.add("metadata", writeStrings(value.metadata())); return out;
    }
    private FrozenMethod.ValueView readValue(JsonObject in) {
        return new FrozenMethod.ValueView(readId(in), readType(object(in, "type")), id(in, "definition"),
                integer(in, "resultIndex"), nullableString(in.get("debugName")), readArray(in, "uses", item ->
                new FrozenMethod.UseView(id(item, "user"), integer(item, "index"))), readStrings(object(in, "metadata")));
    }

    private JsonObject writeOperation(Operation operation) {
        JsonObject out = new JsonObject(); out.addProperty("namespace", operation.code().namespace());
        out.addProperty("name", operation.code().name());
        JsonObject attributes = new JsonObject();
        operation.attributes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> attributes.add(entry.getKey(), writeAttribute(entry.getValue())));
        out.add("attributes", attributes); return out;
    }
    private Operation readOperation(JsonObject in) {
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        object(in, "attributes").entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> attributes.put(entry.getKey(), readAttribute(entry.getValue().getAsJsonObject())));
        return new Operation(new OperationCode(string(in, "namespace"), string(in, "name")), attributes);
    }

    private JsonObject writeType(IrType type) {
        JsonObject out = new JsonObject();
        if (type instanceof PrimitiveType value) { out.addProperty("kind", "primitive"); out.addProperty("name", value.name()); }
        else if (type instanceof SpecialType value) { out.addProperty("kind", "special"); out.addProperty("name", value.name()); }
        else if (type instanceof ReferenceType value) {
            out.addProperty("kind", "reference"); out.addProperty("internalName", value.internalName());
            out.addProperty("nullability", value.nullability().name());
        } else if (type instanceof ArrayType value) {
            out.addProperty("kind", "array"); out.add("element", writeType(value.elementType()));
            out.addProperty("dimensions", value.dimensions()); out.addProperty("nullability", value.nullability().name());
        } else if (type instanceof MethodType value) {
            out.addProperty("kind", "method"); out.add("parameters", array(value.parameterTypes(), this::writeType));
            out.add("return", writeType(value.returnType()));
        } else if (type instanceof UninitializedType value) {
            out.addProperty("kind", "uninitialized"); out.add("allocationSite", id(value.allocationSite()));
            out.add("initializedType", writeType(value.initializedType()));
        } else throw new IrSerializationException("Unsupported IR type " + type.getClass().getName());
        return out;
    }
    private IrType readType(JsonObject in) {
        return switch (string(in, "kind")) {
            case "primitive" -> PrimitiveType.valueOf(string(in, "name"));
            case "special" -> SpecialType.valueOf(string(in, "name"));
            case "reference" -> new ReferenceType(string(in, "internalName"), Nullability.valueOf(string(in, "nullability")));
            case "array" -> new ArrayType(readType(object(in, "element")), integer(in, "dimensions"),
                    Nullability.valueOf(string(in, "nullability")));
            case "method" -> new MethodType(readArray(in, "parameters", this::readType), readType(object(in, "return")));
            case "uninitialized" -> new UninitializedType(id(in, "allocationSite"),
                    (ReferenceType) readType(object(in, "initializedType")));
            default -> throw new IrSerializationException("Unknown IR type kind " + string(in, "kind"));
        };
    }

    private JsonObject writeAttribute(IrAttribute value) {
        JsonObject out = new JsonObject();
        if (value instanceof IrAttribute.StringValue item) { out.addProperty("kind", "string"); out.addProperty("value", item.value()); }
        else if (value instanceof IrAttribute.LongValue item) { out.addProperty("kind", "long"); out.addProperty("value", Long.toString(item.value())); }
        else if (value instanceof IrAttribute.DoubleValue item) { out.addProperty("kind", "double"); out.addProperty("bits", Long.toUnsignedString(Double.doubleToRawLongBits(item.value()), 16)); }
        else if (value instanceof IrAttribute.BooleanValue item) { out.addProperty("kind", "boolean"); out.addProperty("value", item.value()); }
        else if (value instanceof IrAttribute.TypeValue item) { out.addProperty("kind", "type"); out.add("value", writeType(item.value())); }
        else if (value instanceof IrAttribute.BytesValue item) { out.addProperty("kind", "bytes"); out.addProperty("value", Base64.getEncoder().encodeToString(item.value())); }
        else if (value instanceof IrAttribute.ArrayValue item) { out.addProperty("kind", "array"); out.add("value", array(item.values(), this::writeAttribute)); }
        else if (value instanceof IrAttribute.DictionaryValue item) {
            out.addProperty("kind", "dictionary"); JsonObject dictionary = new JsonObject();
            item.values().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> dictionary.add(entry.getKey(), writeAttribute(entry.getValue())));
            out.add("value", dictionary);
        } else throw new IrSerializationException("Unsupported attribute " + value.getClass().getName());
        return out;
    }
    private IrAttribute readAttribute(JsonObject in) {
        return switch (string(in, "kind")) {
            case "string" -> IrAttribute.of(string(in, "value"));
            case "long" -> IrAttribute.of(Long.parseLong(string(in, "value")));
            case "double" -> IrAttribute.of(Double.longBitsToDouble(Long.parseUnsignedLong(string(in, "bits"), 16)));
            case "boolean" -> IrAttribute.of(in.get("value").getAsBoolean());
            case "type" -> IrAttribute.of(readType(object(in, "value")));
            case "bytes" -> new IrAttribute.BytesValue(Base64.getDecoder().decode(string(in, "value")));
            case "array" -> new IrAttribute.ArrayValue(readArray(in, "value", this::readAttribute));
            case "dictionary" -> {
                Map<String, IrAttribute> values = new LinkedHashMap<>();
                object(in, "value").entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> values.put(entry.getKey(), readAttribute(entry.getValue().getAsJsonObject())));
                yield new IrAttribute.DictionaryValue(values);
            }
            default -> throw new IrSerializationException("Unknown attribute kind " + string(in, "kind"));
        };
    }

    private JsonObject idObject(IrId id) { JsonObject out = new JsonObject(); out.add("id", id(id)); return out; }
    private JsonElement id(IrId id) { return id == null ? JsonNull.INSTANCE : gson.toJsonTree(Long.toUnsignedString(id.value())); }
    private JsonElement nullableId(IrId id) { return id(id); }
    private IrId readId(JsonObject in) { return id(in, "id"); }
    private IrId id(JsonObject in, String name) { return new IrId(Long.parseUnsignedLong(string(in, name))); }
    private IrId nullableId(JsonElement value) { return value == null || value.isJsonNull() ? null : new IrId(Long.parseUnsignedLong(value.getAsString())); }
    private JsonArray ids(List<IrId> values) { return array(values, this::id); }
    private List<IrId> readIds(JsonObject in, String name) { return readElements(in, name, item -> new IrId(Long.parseUnsignedLong(item.getAsString()))); }
    private JsonElement nullableString(String value) { return value == null ? JsonNull.INSTANCE : gson.toJsonTree(value); }
    private String nullableString(JsonElement value) { return value == null || value.isJsonNull() ? null : value.getAsString(); }
    private JsonArray strings(List<String> values) { return array(values, gson::toJsonTree); }
    private List<String> readStringArray(JsonObject in, String name) { return readElements(in, name, JsonElement::getAsString); }

    private JsonObject writeStrings(Map<String, String> values) {
        JsonObject out = new JsonObject(); values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.addProperty(entry.getKey(), entry.getValue())); return out;
    }
    private Map<String, String> readStrings(JsonObject in) {
        Map<String, String> result = new LinkedHashMap<>(); in.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString())); return result;
    }

    private <T> JsonArray array(List<T> values, java.util.function.Function<T, JsonElement> writer) {
        JsonArray out = new JsonArray(); values.forEach(value -> out.add(writer.apply(value))); return out;
    }
    private <T> List<T> readArray(JsonObject in, String name, java.util.function.Function<JsonObject, T> reader) {
        List<T> out = new ArrayList<>(); for (JsonElement value : in.getAsJsonArray(name)) out.add(reader.apply(value.getAsJsonObject())); return out;
    }
    private <T> List<T> readElements(JsonObject in, String name, ElementReader<T> reader) {
        List<T> out = new ArrayList<>(); for (JsonElement value : in.getAsJsonArray(name)) out.add(reader.read(value)); return out;
    }
    private JsonObject object(JsonObject in, String name) { return in.getAsJsonObject(name); }
    private String string(JsonObject in, String name) { return in.get(name).getAsString(); }
    private int integer(JsonObject in, String name) { return in.get(name).getAsInt(); }
    private long unsignedLong(JsonObject in, String name) { return Long.parseUnsignedLong(string(in, name)); }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    @FunctionalInterface private interface ElementReader<T> { T read(JsonElement value); }
}
