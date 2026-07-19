package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StructuralHardeningTransformer extends Transformer {
    @Override
    public String getName() {
        return "structural-hardening";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        int attributesPerClass = intOption(context, "attributes-per-class", 4, 0, 32);
        int payloadBytes = intOption(context, "payload-bytes", 256, 0, 4096);
        boolean methodAttributes = booleanOption(context, "method-attributes", true);
        boolean fieldAttributes = booleanOption(context, "field-attributes", true);
        int classes = 0;
        int attributes = 0;

        for (ClassNode classNode : context.pool().getClasses()) {
            if (!eligible(classNode, context)) {
                continue;
            }
            for (int i = 0; i < attributesPerClass; i++) {
                addAttribute(classNode, "FrostStructural" + i, payload(classNode.name, "class", i, payloadBytes));
                attributes++;
            }
            if (methodAttributes) {
                for (MethodNode method : classNode.methods) {
                    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                        addAttribute(method, "FrostMethod" + Math.floorMod(method.name.hashCode(), 17),
                                payload(classNode.name, method.name + method.desc, 0, Math.min(payloadBytes, 512)));
                        attributes++;
                    }
                }
            }
            if (fieldAttributes) {
                for (FieldNode field : classNode.fields) {
                    addAttribute(field, "FrostField" + Math.floorMod(field.name.hashCode(), 17),
                            payload(classNode.name, field.name + field.desc, 0, Math.min(payloadBytes, 512)));
                    attributes++;
                }
            }
            context.pool().markDirty(classNode.name);
            classes++;
        }

        context.stats().add("structuralHardenedClasses", classes);
        context.stats().add("structuralOpaqueAttributes", attributes);
        log("Added {} verifier-safe opaque attributes to {} classes", attributes, classes);
    }

    private boolean eligible(ClassNode classNode, Context context) {
        if (classNode.name.startsWith("dev/frost/runtime/")) {
            return false;
        }
        return shouldProcess(classNode.name, context.config(),
                context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions());
    }

    private void addAttribute(ClassNode classNode, String name, byte[] payload) {
        if (classNode.attrs == null) {
            classNode.attrs = new ArrayList<>();
        }
        classNode.attrs.add(new OpaqueAttribute(name, payload));
    }

    private void addAttribute(MethodNode method, String name, byte[] payload) {
        if (method.attrs == null) {
            method.attrs = new ArrayList<>();
        }
        method.attrs.add(new OpaqueAttribute(name, payload));
    }

    private void addAttribute(FieldNode field, String name, byte[] payload) {
        if (field.attrs == null) {
            field.attrs = new ArrayList<>();
        }
        field.attrs.add(new OpaqueAttribute(name, payload));
    }

    private byte[] payload(String owner, String kind, int index, int size) {
        if (size <= 0) {
            return new byte[0];
        }
        byte[] seed = (owner + '\n' + kind + '\n' + index).getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[size];
        int offset = 0;
        int round = 0;
        while (offset < output.length) {
            byte[] block = digest(seed, round++);
            int length = Math.min(block.length, output.length - offset);
            System.arraycopy(block, 0, output, offset, length);
            offset += length;
        }
        return output;
    }

    private byte[] digest(byte[] seed, int round) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed);
            digest.update((byte) round);
            return digest.digest();
        } catch (Exception exception) {
            byte[] fallback = Arrays.copyOf(seed, 32);
            Arrays.fill(fallback, (byte) round);
            return fallback;
        }
    }

    private int intOption(Context context, String key, int fallback, int min, int max) {
        try {
            Object value = context.config().getOptions().get(key);
            int parsed = value == null ? fallback : Integer.parseInt(value.toString());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static final class OpaqueAttribute extends Attribute {
        private final byte[] data;

        private OpaqueAttribute(String type, byte[] data) {
            super(type);
            this.data = data;
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            return new ByteVector(data.length).putByteArray(data, 0, data.length);
        }
    }
}
