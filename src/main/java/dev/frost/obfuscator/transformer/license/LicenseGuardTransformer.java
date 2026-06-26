package dev.frost.obfuscator.transformer.license;

import dev.frost.license.FrostLicenseRuntime;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class LicenseGuardTransformer extends Transformer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RUNTIME_CLASS = "dev/frost/license/FrostLicenseRuntime";
    private static final String VERIFY_DESC = "()V";

    @Override
    public String getName() {
        return "license-guard";
    }

    @Override
    public String getCategory() {
        return "License";
    }

    @Override
    public Priority priority() {
        return Priority.PRE_OBFUSCATION;
    }

    @Override
    public void transform(Context context) {
        TransformerConfig config = context.config();
        Properties policy = buildPolicy(config);
        ClassPool pool = context.pool();

        try {
            ClassNode runtime = patchRuntime(policy);
            pool.addClass(runtime.name, runtime);
            pool.markDirty(runtime.name);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to inject Frost license runtime", exception);
        }

        String coverage = option(config, "entrypoints", "coverage", "injection-mode").toLowerCase(Locale.ROOT);
        boolean injectClinit = boolOption(config, true, "inject-clinit", "injectClinit");
        int guardedMethods = 0;
        int guardedClasses = 0;

        List<ClassNode> classes = new ArrayList<>(pool.getClasses());
        for (ClassNode classNode : classes) {
            if (RUNTIME_CLASS.equals(classNode.name)) {
                continue;
            }
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }

            boolean entryClass = isEntryClass(context, classNode.name);
            int classGuards = switch (coverage) {
                case "all-methods", "all" -> injectAllMethods(classNode);
                case "selected" -> injectEntrypoints(classNode, true);
                default -> injectEntrypoints(classNode, entryClass);
            };

            if (injectClinit && (entryClass || coverage.equals("all-classes"))) {
                classGuards += injectClinit(classNode);
            }

            if (classGuards > 0) {
                guardedMethods += classGuards;
                guardedClasses++;
                pool.markDirty(classNode.name);
            }
        }

        context.stats().add("licenseGuardedClasses", guardedClasses);
        context.stats().add("licenseGuardedMethods", guardedMethods);
        log("Injected license guard into {} methods across {} classes", guardedMethods, guardedClasses);
    }

    private Properties buildPolicy(TransformerConfig config) {
        Properties properties = new Properties();
        put(properties, "product", option(config, "", "product"));
        put(properties, "license-id", option(config, "", "license-id", "licenseId"));
        put(properties, "customer", option(config, "", "customer"));
        put(properties, "failure-message", option(config, "License validation failed", "failure-message", "message"));
        put(properties, "failure-action", option(config, "throw", "failure-action", "fail-action"));
        put(properties, "required-features", option(config, "", "required-features", "features"));
        put(properties, "expires-at-epoch", Long.toString(parseTime(option(config, "", "expires-at", "expiresAt", "expiration"))));
        put(properties, "not-before-epoch", Long.toString(parseTime(option(config, "", "not-before", "notBefore"))));
        long graceDays = longOption(config, 0L, "grace-days", "graceDays");
        put(properties, "grace-millis", Long.toString(Math.max(0L, graceDays) * 86_400_000L));

        boolean hwidEnabled = boolOption(config, false, "hwid-enabled", "hwid");
        String hwidSalt = option(config, randomHex(16), "hwid-salt", "salt");
        String hwidComponents = option(config, "mac,hostname,os,user,machine-id", "hwid-components", "hwidComponents");
        String allowedHwids = option(config, "", "allowed-hwids", "allowedHwids", "hwids");
        if (hwidEnabled && boolOption(config, false, "bind-current-machine", "bindCurrentMachine") && allowedHwids.isBlank()) {
            allowedHwids = FrostLicenseRuntime.currentHwid(hwidSalt, hwidComponents);
        }
        put(properties, "hwid-enabled", Boolean.toString(hwidEnabled));
        put(properties, "hwid-salt", hwidSalt);
        put(properties, "hwid-components", hwidComponents);
        put(properties, "allowed-hwids", allowedHwids);
        put(properties, "print-hwid-on-failure", Boolean.toString(boolOption(config, true, "print-hwid-on-failure", "printHwidOnFailure")));

        put(properties, "token", option(config, "", "token", "license-token", "licenseToken"));
        put(properties, "token-public-key", option(config, "", "token-public-key", "tokenPublicKey"));
        put(properties, "token-secret", option(config, "", "token-secret", "tokenSecret"));

        put(properties, "clock-rollback", Boolean.toString(boolOption(config, true, "clock-rollback", "clockRollback")));
        put(properties, "state-required", Boolean.toString(boolOption(config, false, "state-required", "stateRequired")));
        put(properties, "state-file", option(config, "", "state-file", "stateFile"));
        put(properties, "state-key", option(config, randomHex(32), "state-key", "stateKey"));
        return properties;
    }

    private ClassNode patchRuntime(Properties policy) throws IOException {
        String configBase64 = encodePolicy(policy);
        try (InputStream inputStream = LicenseGuardTransformer.class.getResourceAsStream("/dev/frost/license/FrostLicenseRuntime.class")) {
            if (inputStream == null) {
                throw new IOException("Missing FrostLicenseRuntime class resource");
            }
            ClassReader reader = new ClassReader(inputStream);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            for (MethodNode method : node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc
                            && "FROST_LICENSE_CONFIG_PLACEHOLDER".equals(ldc.cst)) {
                        ldc.cst = configBase64;
                    }
                }
            }
            return node;
        }
    }

    private String encodePolicy(Properties policy) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        policy.store(output, "Frost license policy");
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private boolean isEntryClass(Context context, String internalName) {
        String pluginMain = context.jar().getCurrentPluginMainClass();
        if (pluginMain != null && pluginMain.replace('.', '/').equals(internalName)) {
            return true;
        }
        String manifestMain = context.jar().getManifestMainClass();
        return manifestMain != null && manifestMain.replace('.', '/').equals(internalName);
    }

    private int injectEntrypoints(ClassNode classNode, boolean entryClass) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            if (!isConcrete(method)) {
                continue;
            }
            boolean main = method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V");
            boolean pluginLifecycle = entryClass && (method.name.equals("onLoad")
                    || method.name.equals("onEnable")
                    || method.name.equals("onDisable"));
            if (main || pluginLifecycle) {
                method.instructions.insert(guardCall());
                count++;
            }
        }
        return count;
    }

    private int injectAllMethods(ClassNode classNode) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            if (isConcrete(method) && !method.name.equals("<clinit>")) {
                method.instructions.insert(guardCall());
                count++;
            }
        }
        return count;
    }

    private int injectClinit(ClassNode classNode) {
        MethodNode clinit = findMethod(classNode, "<clinit>", VERIFY_DESC);
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", VERIFY_DESC, null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            classNode.methods.add(clinit);
        }
        clinit.instructions.insert(guardCall());
        return 1;
    }

    private MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private boolean isConcrete(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        if (method.name.equals("<init>")) {
            return false;
        }
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private InsnList guardCall() {
        InsnList instructions = new InsnList();
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_CLASS, "verify", VERIFY_DESC, false));
        return instructions;
    }

    private long parseTime(String value) {
        if (value == null || value.isBlank() || value.equals("0")) {
            return 0L;
        }
        String trimmed = value.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid license date: " + value);
        }
    }

    private void put(Properties properties, String key, String value) {
        properties.setProperty(key, value == null ? "" : value);
    }

    private String option(TransformerConfig config, String fallback, String... keys) {
        for (String key : keys) {
            Object value = config.getOptions().get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return fallback;
    }

    private boolean boolOption(TransformerConfig config, boolean fallback, String... keys) {
        String value = option(config, null, keys);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private long longOption(TransformerConfig config, long fallback, String... keys) {
        String value = option(config, null, keys);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value.trim());
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return HexFormat.of().formatHex(data);
    }
}
