package dev.frost.ir.bytecode;

import dev.frost.ir.model.IrAttribute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

/** Lossless, serializable Frost-IR attributes for JVM bootstrap methods and constants. */
public final class JvmBootstrapAttributes {
    private JvmBootstrapAttributes() {}

    public static Map<String, IrAttribute> dynamicCallSite(String name, String descriptor,
                                                           Handle bootstrap, Object... arguments) {
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        attributes.put("name", IrAttribute.of(name));
        attributes.put("descriptor", IrAttribute.of(descriptor));
        attributes.put("bootstrap", IrAttribute.of(bootstrap.toString()));
        attributes.put("bootstrap_handle", encodeHandle(bootstrap));
        attributes.put("bootstrap_args", encodeArguments(arguments));
        return Map.copyOf(attributes);
    }

    public static Map<String, IrAttribute> dynamicConstant(ConstantDynamic dynamic) {
        Objects.requireNonNull(dynamic, "dynamic");
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        attributes.put("name", IrAttribute.of(dynamic.getName()));
        attributes.put("descriptor", IrAttribute.of(dynamic.getDescriptor()));
        attributes.put("bootstrap", IrAttribute.of(dynamic.getBootstrapMethod().toString()));
        attributes.put("bootstrap_handle", encodeHandle(dynamic.getBootstrapMethod()));
        attributes.put("bootstrap_args", encodeArguments(arguments(dynamic)));
        return Map.copyOf(attributes);
    }

    public static Handle bootstrapHandle(Map<String, IrAttribute> attributes) {
        return decodeHandle(required(attributes, "bootstrap_handle"));
    }

    public static Object[] bootstrapArguments(Map<String, IrAttribute> attributes) {
        IrAttribute encoded = required(attributes, "bootstrap_args");
        if (!(encoded instanceof IrAttribute.ArrayValue array)) {
            throw new IllegalArgumentException("bootstrap_args is not an attribute array");
        }
        return array.values().stream().map(JvmBootstrapAttributes::decodeConstant).toArray();
    }

    public static ConstantDynamic constantDynamic(Map<String, IrAttribute> attributes) {
        return new ConstantDynamic(string(attributes, "name"), string(attributes, "descriptor"),
                bootstrapHandle(attributes), bootstrapArguments(attributes));
    }

    private static IrAttribute encodeArguments(Object[] arguments) {
        List<IrAttribute> encoded = new ArrayList<>(arguments.length);
        for (Object argument : arguments) encoded.add(encodeConstant(argument));
        return new IrAttribute.ArrayValue(encoded);
    }

    private static IrAttribute encodeConstant(Object value) {
        Map<String, IrAttribute> encoded = new LinkedHashMap<>();
        if (value instanceof Integer number) {
            encoded.put("kind", IrAttribute.of("int"));
            encoded.put("value", IrAttribute.of(number.longValue()));
        } else if (value instanceof Long number) {
            encoded.put("kind", IrAttribute.of("long"));
            encoded.put("value", IrAttribute.of(number));
        } else if (value instanceof Float number) {
            encoded.put("kind", IrAttribute.of("float"));
            encoded.put("value", IrAttribute.of(number.doubleValue()));
        } else if (value instanceof Double number) {
            encoded.put("kind", IrAttribute.of("double"));
            encoded.put("value", IrAttribute.of(number));
        } else if (value instanceof String text) {
            encoded.put("kind", IrAttribute.of("string"));
            encoded.put("value", IrAttribute.of(text));
        } else if (value instanceof Type type) {
            encoded.put("kind", IrAttribute.of("type"));
            encoded.put("descriptor", IrAttribute.of(type.getDescriptor()));
        } else if (value instanceof Handle handle) {
            encoded.put("kind", IrAttribute.of("handle"));
            encoded.put("value", encodeHandle(handle));
        } else if (value instanceof ConstantDynamic dynamic) {
            encoded.put("kind", IrAttribute.of("constant_dynamic"));
            encoded.put("name", IrAttribute.of(dynamic.getName()));
            encoded.put("descriptor", IrAttribute.of(dynamic.getDescriptor()));
            encoded.put("bootstrap_handle", encodeHandle(dynamic.getBootstrapMethod()));
            encoded.put("bootstrap_args", encodeArguments(arguments(dynamic)));
        } else {
            throw new IllegalArgumentException("Unsupported JVM bootstrap constant: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return new IrAttribute.DictionaryValue(encoded);
    }

    private static Object decodeConstant(IrAttribute attribute) {
        Map<String, IrAttribute> values = dictionary(attribute);
        return switch (string(values, "kind")) {
            case "int" -> Math.toIntExact(number(values, "value"));
            case "long" -> number(values, "value");
            case "float" -> (float) decimal(values, "value");
            case "double" -> decimal(values, "value");
            case "string" -> string(values, "value");
            case "type" -> Type.getType(string(values, "descriptor"));
            case "handle" -> decodeHandle(required(values, "value"));
            case "constant_dynamic" -> new ConstantDynamic(string(values, "name"),
                    string(values, "descriptor"), decodeHandle(required(values, "bootstrap_handle")),
                    decodeArguments(required(values, "bootstrap_args")));
            default -> throw new IllegalArgumentException("Unknown JVM bootstrap constant kind");
        };
    }

    private static Object[] decodeArguments(IrAttribute attribute) {
        if (!(attribute instanceof IrAttribute.ArrayValue array)) {
            throw new IllegalArgumentException("bootstrap arguments are not an array");
        }
        return array.values().stream().map(JvmBootstrapAttributes::decodeConstant).toArray();
    }

    private static IrAttribute encodeHandle(Handle handle) {
        Objects.requireNonNull(handle, "handle");
        return new IrAttribute.DictionaryValue(Map.of(
                "tag", IrAttribute.of((long) handle.getTag()),
                "owner", IrAttribute.of(handle.getOwner()),
                "name", IrAttribute.of(handle.getName()),
                "descriptor", IrAttribute.of(handle.getDesc()),
                "interface", IrAttribute.of(handle.isInterface())));
    }

    private static Handle decodeHandle(IrAttribute attribute) {
        Map<String, IrAttribute> values = dictionary(attribute);
        return new Handle(Math.toIntExact(number(values, "tag")), string(values, "owner"),
                string(values, "name"), string(values, "descriptor"), bool(values, "interface"));
    }

    private static Object[] arguments(ConstantDynamic dynamic) {
        Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
        for (int index = 0; index < arguments.length; index++) {
            arguments[index] = dynamic.getBootstrapMethodArgument(index);
        }
        return arguments;
    }

    private static Map<String, IrAttribute> dictionary(IrAttribute attribute) {
        if (attribute instanceof IrAttribute.DictionaryValue dictionary) return dictionary.values();
        throw new IllegalArgumentException("attribute is not a dictionary");
    }

    private static IrAttribute required(Map<String, IrAttribute> values, String name) {
        IrAttribute value = values.get(name);
        if (value == null) throw new IllegalArgumentException("Missing JVM bootstrap attribute " + name);
        return value;
    }

    private static String string(Map<String, IrAttribute> values, String name) {
        if (required(values, name) instanceof IrAttribute.StringValue text) return text.value();
        throw new IllegalArgumentException(name + " is not a string attribute");
    }

    private static long number(Map<String, IrAttribute> values, String name) {
        if (required(values, name) instanceof IrAttribute.LongValue number) return number.value();
        throw new IllegalArgumentException(name + " is not an integer attribute");
    }

    private static double decimal(Map<String, IrAttribute> values, String name) {
        if (required(values, name) instanceof IrAttribute.DoubleValue number) return number.value();
        throw new IllegalArgumentException(name + " is not a floating-point attribute");
    }

    private static boolean bool(Map<String, IrAttribute> values, String name) {
        if (required(values, name) instanceof IrAttribute.BooleanValue bool) return bool.value();
        throw new IllegalArgumentException(name + " is not a boolean attribute");
    }
}
