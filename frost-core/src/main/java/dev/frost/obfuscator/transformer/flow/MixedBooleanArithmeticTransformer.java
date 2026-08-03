package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.ir.pass.MixedBooleanArithmeticPass;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Runs mixed Boolean-arithmetic rewrites over typed SSA def-use chains. */
public final class MixedBooleanArithmeticTransformer extends Transformer {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_OUTPUT_INSTRUCTIONS = 20_000;
    private static final int MAX_IMPORT_INSTRUCTIONS = 4_096;
    private static final long MAX_ANALYSIS_FRAME_CELLS = 1_000_000L;

    @Override
    public String getName() {
        return "mixed-boolean-arithmetic";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        Counts counts = apply(context.pool(), context.config());
        context.stats().add("mbaOperations", counts.arithmetic.sum());
        context.stats().add("mbaConditionals", counts.conditionals.sum());
        context.stats().add("mbaSkippedMethods", counts.skipped.sum());
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, config);
    }

    private Counts apply(ClassPool pool, TransformerConfig config) {
        int probability = intOption(config, "probability", 70, 0, 100);
        int rounds = intOption(config, "rounds", 1, 1, 3);
        int polynomialDegree = intOption(config, "polynomial-degree", 3, 1, 5);
        int zeroTerms = intOption(config, "zero-terms", 2, 0, 4);
        int maximumPerMethod = intOption(config, "max-per-method", 64, 0, 512);
        int maximumPerClass = intOption(config, "max-per-class", 256, 0, 4_096);
        int maximumMethodInstructions = intOption(config, "max-method-instructions", 6_000,
                64, MAX_OUTPUT_INSTRUCTIONS);
        int maximumOutputInstructions = intOption(config, "max-output-method-instructions", 12_000,
                maximumMethodInstructions, MAX_OUTPUT_INSTRUCTIONS);
        int maximumIrInstructions = intOption(config, "max-ir-instructions", 768,
                64, 2_048);
        boolean conditionals = booleanOption(config, "conditionals", true);
        boolean switchKeys = booleanOption(config, "switch-keys", true);
        boolean longComparisons = booleanOption(config, "long-comparisons", true);
        boolean includeSynthetic = booleanOption(config, "include-synthetic", false);
        Set<String> operations = operations(config);
        long configuredSeed = longOption(config, "seed", 0L);
        long runSeed = configuredSeed == 0L ? SECURE_RANDOM.nextLong() : configuredSeed;
        Counts counts = new Counts();
        IrMethodPassAdapter adapter = new IrMethodPassAdapter();

        pool.forEachClass(owner -> {
            if (!shouldProcess(owner.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) return;
            int changedInClass = 0;
            boolean classChanged = false;
            for (int methodIndex = 0; methodIndex < owner.methods.size(); methodIndex++) {
                MethodNode method = owner.methods.get(methodIndex);
                if (!eligible(method, maximumMethodInstructions, includeSynthetic)
                        || changedInClass >= maximumPerClass) continue;
                int maximum = Math.min(maximumPerMethod, maximumPerClass - changedInClass);
                MixedBooleanArithmeticPass pass = new MixedBooleanArithmeticPass(
                        new MixedBooleanArithmeticPass.Options(probability, maximum,
                                maximumIrInstructions, rounds,
                                polynomialDegree, zeroTerms, operations, conditionals,
                                switchKeys, longComparisons));
                var result = adapter.run(owner.name, method, pass,
                        methodSeed(runSeed, owner, method));
                if (!result.changed()) {
                    if (result.status() != IrMethodPassAdapter.Status.UNCHANGED) counts.skipped.increment();
                    continue;
                }
                MethodNode output = result.output().orElseThrow();
                if (output.instructions.size() > maximumOutputInstructions) {
                    counts.skipped.increment();
                    continue;
                }
                owner.methods.set(methodIndex, output);
                int changed = Math.toIntExact(result.metric("arithmetic")
                        + result.metric("conditionals"));
                changedInClass += changed;
                counts.arithmetic.add(result.metric("arithmetic"));
                counts.conditionals.add(result.metric("conditionals"));
                classChanged = true;
            }
            if (classChanged) {
                pool.markFramesDirty(owner.name);
                detail("Generated {} polymorphic MBA expressions in {}", changedInClass, owner.name);
            }
        });
        return counts;
    }

    private static boolean eligible(MethodNode method, int maximumInstructions, boolean includeSynthetic) {
        if (method.instructions == null || method.instructions.size() == 0
                || method.instructions.size() > Math.min(maximumInstructions, MAX_IMPORT_INSTRUCTIONS)
                || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || (!includeSynthetic && (method.access & Opcodes.ACC_SYNTHETIC) != 0)) {
            return false;
        }
        long frameWidth = Math.max(1, method.maxLocals) + (long) Math.max(1, method.maxStack);
        return (long) method.instructions.size() * frameWidth <= MAX_ANALYSIS_FRAME_CELLS;
    }

    private Set<String> operations(TransformerConfig config) {
        Object configured = config.getOptions().get("operations");
        String text = configured == null ? "add,sub,mul,and,or,xor,neg" : configured.toString();
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(text.split("[,;\\s]+")).map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT)).forEach(result::add);
        return Set.copyOf(result);
    }

    private int intOption(TransformerConfig config, String key, int fallback, int minimum, int maximum) {
        Object value = config.getOptions().get(key);
        int parsed = fallback;
        if (value instanceof Number number) parsed = number.intValue();
        else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private long longOption(TransformerConfig config, String key, long fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number number) return number.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        return value == null ? fallback : value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private long methodSeed(long runSeed, ClassNode owner, MethodNode method) {
        return mix(runSeed ^ stableHash(owner.name + '.' + method.name + method.desc));
    }

    private long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final class Counts {
        private final LongAdder arithmetic = new LongAdder();
        private final LongAdder conditionals = new LongAdder();
        private final LongAdder skipped = new LongAdder();
    }
}
