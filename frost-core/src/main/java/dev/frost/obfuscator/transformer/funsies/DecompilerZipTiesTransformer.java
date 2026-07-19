package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Adds bounded, verifier-safe recursive generic metadata. Real cyclic inheritance is deliberately
 * not emitted because the JVM rejects it during class loading, but Signature attributes can still
 * contain recursive type-variable graphs that many decompilers try to render.
 */
public final class DecompilerZipTiesTransformer extends Transformer {
    @Override
    public String getName() {
        return "decompiler-zip-ties";
    }

    @Override
    public String getCategory() {
        return "Funsies";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        int depth = intOption(context, "generic-depth", 96, 8, 512);
        int fields = intOption(context, "fields-per-class", 2, 1, 16);
        int methods = intOption(context, "methods-per-class", 2, 0, 16);
        boolean classSignature = booleanOption(context, "class-signature", true);
        int changed = 0;
        for (ClassNode node : context.pool().getClasses()) {
            if (!shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) {
                continue;
            }
            if (classSignature && node.signature == null) {
                node.signature = cyclicTypeVariableSignature(depth);
            }
            for (int i = 0; i < fields; i++) {
                String name = "__frost$zip$tie$" + i;
                if (node.fields.stream().anyMatch(field -> field.name.equals(name))) {
                    continue;
                }
                node.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        name,
                        "Ljava/util/List;",
                        nestedListSignature(node.name, depth),
                        null
                ));
            }
            for (int i = 0; i < methods; i++) {
                String name = "__frost$zip$method$" + i;
                if (node.methods.stream().anyMatch(method -> method.name.equals(name))) {
                    continue;
                }
                node.methods.add(zipMethod(name, node.name, depth));
            }
            context.pool().markDirty(node.name);
            changed++;
        }
        context.stats().add("zipTiedClasses", changed);
        log("Added bounded recursive generic metadata (depth {}) to {} classes", depth, changed);
    }

    private String nestedListSignature(String owner, int depth) {
        StringBuilder signature = new StringBuilder(depth * 18 + owner.length() + 4);
        for (int i = 0; i < depth; i++) {
            signature.append("Ljava/util/List<");
        }
        signature.append('L').append(owner).append(';');
        for (int i = 0; i < depth; i++) {
            signature.append(">;");
        }
        return signature.toString();
    }

    private String cyclicTypeVariableSignature(int depth) {
        int variables = Math.max(2, Math.min(64, depth / 4));
        StringBuilder signature = new StringBuilder(variables * 40 + 20);
        signature.append('<');
        for (int i = 0; i < variables; i++) {
            signature.append("__F").append(i).append(":Ljava/util/List<T__F")
                    .append((i + 1) % variables)
                    .append(";>;");
        }
        signature.append(">Ljava/lang/Object;");
        return signature.toString();
    }

    private MethodNode zipMethod(String name, String owner, int depth) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "()Ljava/util/List;",
                "()" + nestedListSignature(owner, depth),
                null
        );
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        return method;
    }

    private int intOption(Context context, String key, int fallback, int min, int max) {
        Object value = context.config().getOptions().get(key);
        int parsed = fallback;
        try {
            if (value != null) parsed = Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
