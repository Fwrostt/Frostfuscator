package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

final class RuntimeRelocator {
    private static final SecureRandom RANDOM = new SecureRandom();

    private RuntimeRelocator() {
    }

    static RuntimeHandle inject(Context context, String originalName, String configuredClass, String configuredMethod, String descriptor) {
        String targetName = configuredClass == null || configuredClass.isBlank()
                ? randomRuntimeName(context)
                : sanitizeInternalName(configuredClass);
        String methodName = configuredMethod == null || configuredMethod.isBlank()
                ? randomIdentifier()
                : sanitizeIdentifier(configuredMethod);

        if (!context.pool().contains(targetName)) {
            ClassNode original = readRuntime(originalName);
            ClassNode relocated = new ClassNode();
            original.accept(new ClassRemapper(relocated, new Remapper() {
                @Override
                public String map(String internalName) {
                    return originalName.equals(internalName) ? targetName : internalName;
                }
            }));
            relocated.name = targetName;
            relocated.sourceFile = null;
            relocated.sourceDebug = null;
            for (MethodNode method : relocated.methods) {
                if ("verify".equals(method.name) && descriptor.equals(method.desc)) {
                    method.name = methodName;
                }
            }
            context.pool().addClass(relocated.name, relocated);
            context.pool().markDirty(relocated.name);
        }
        return new RuntimeHandle(targetName, methodName);
    }

    private static ClassNode readRuntime(String originalName) {
        try (InputStream input = RuntimeRelocator.class.getResourceAsStream("/" + originalName + ".class")) {
            if (input == null) {
                throw new IllegalStateException("Missing embedded runtime " + originalName);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, ClassReader.EXPAND_FRAMES);
            return node;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not inject runtime " + originalName, exception);
        }
    }

    private static String randomRuntimeName(Context context) {
        String prefix = "META-INF".equals(context.inputPath().getFileName().toString()) ? "" : firstApplicationPackage(context);
        if (!prefix.isBlank()) {
            prefix += "/";
        }
        return prefix + randomIdentifier() + "$" + randomIdentifier();
    }

    private static String firstApplicationPackage(Context context) {
        for (ClassNode classNode : context.pool().getClasses()) {
            if (classNode.name.startsWith("dev/frost/")) {
                continue;
            }
            int slash = classNode.name.lastIndexOf('/');
            if (slash > 0) {
                return classNode.name.substring(0, slash);
            }
        }
        return "__frost";
    }

    private static String randomIdentifier() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "_$" + HexFormat.of().formatHex(bytes);
    }

    private static String sanitizeInternalName(String value) {
        String cleaned = value.replace('.', '/').replaceAll("[^A-Za-z0-9_$/]", "");
        if (cleaned.isBlank()) {
            return "__frost/" + randomIdentifier();
        }
        return cleaned;
    }

    private static String sanitizeIdentifier(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9_$]", "");
        if (cleaned.isBlank() || Character.isDigit(cleaned.charAt(0))) {
            return randomIdentifier();
        }
        return cleaned.toLowerCase(Locale.ROOT).startsWith("verify") ? randomIdentifier() : cleaned;
    }

    record RuntimeHandle(String owner, String method) {
    }
}
