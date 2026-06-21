package dev.frost.obfuscator.jni;

import dev.frost.obfuscator.config.FrostJNIConfig;
import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.engine.JarProcessor;
import dev.frost.obfuscator.jni.compiler.CompilationResult;
import dev.frost.obfuscator.jni.compiler.CompilerKind;
import dev.frost.obfuscator.jni.compiler.CompilerInput;
import dev.frost.obfuscator.jni.compiler.JniSymbolRegistry;
import dev.frost.obfuscator.jni.compiler.NativeBuildPipeline;
import dev.frost.obfuscator.jni.compiler.NativeLibrary;
import dev.frost.obfuscator.jni.compiler.TargetPlatform;
import dev.frost.obfuscator.jni.core.asm.AsmClassParser;
import dev.frost.obfuscator.jni.core.ir.ClassToIRConverter;
import dev.frost.obfuscator.jni.core.ir.IRClass;
import dev.frost.obfuscator.jni.core.ir.IRInstruction;
import dev.frost.obfuscator.jni.core.ir.IRMethod;
import dev.frost.obfuscator.jni.core.ir.IROpcode;
import dev.frost.obfuscator.jni.core.model.ClassModel;
import dev.frost.obfuscator.jni.core.model.DynamicInvocationReference;
import dev.frost.obfuscator.jni.core.model.MethodModel;
import dev.frost.obfuscator.jni.core.selection.NativeSelectionConfig;
import dev.frost.obfuscator.jni.core.selection.NativeSelector;
import dev.frost.obfuscator.jni.desugar.InvokeDynamicDesugarer;
import dev.frost.obfuscator.jni.generator.cpp.CppGenerator;
import dev.frost.obfuscator.jni.generator.cpp.GeneratedCppClass;
import dev.frost.obfuscator.jni.generator.cpp.JniNameMangler;
import dev.frost.obfuscator.jni.generator.cpp.translator.DynamicInvocationTranslator;
import dev.frost.obfuscator.jni.loader.LibraryExtractor;
import dev.frost.obfuscator.jni.loader.Native;
import dev.frost.obfuscator.jni.loader.NativeLoader;
import dev.frost.obfuscator.jni.loader.RuntimeResource;
import dev.frost.obfuscator.jni.patcher.BridgeMetadataGenerator;
import dev.frost.obfuscator.jni.patcher.MethodMappingRegistry;
import dev.frost.obfuscator.jni.patcher.NativeMethodPlan;
import dev.frost.obfuscator.jni.patcher.NativeMethodTransformer;
import dev.frost.obfuscator.jni.patcher.PatchPlan;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FrostJNIProtectionService {
    private static final Set<String> INTERNAL_PREFIXES = Set.of(
            "dev/frost/obfuscator/jni/",
            "dev/frost/obfuscator/runtime/"
    );

    private final AsmClassParser parser = new AsmClassParser();
    private final ClassToIRConverter irConverter = new ClassToIRConverter();
    private final CppGenerator cppGenerator = new CppGenerator();
    private final NativeBuildPipeline buildPipeline = new NativeBuildPipeline();
    private final NativeMethodTransformer methodTransformer = new NativeMethodTransformer();
    private final BridgeMetadataGenerator metadataGenerator = new BridgeMetadataGenerator();
    private final JniNameMangler nameMangler = new JniNameMangler();
    private final RuntimeResource runtimeResource = new RuntimeResource();
    private final NativeProtectionHooks hooks = new NativeProtectionHooks();
    private final InvokeDynamicDesugarer invokeDynamicDesugarer = new InvokeDynamicDesugarer();

    public FrostJNIResult protect(NativeProtectionRequest request) throws IOException, InterruptedException {
        FrostJNIConfig config = request.config();
        Path workDirectory = resolveWorkDirectory(config, request.outputPath());
        Path sourceDirectory = workDirectory.resolve("sources");
        Path nativeDirectory = workDirectory.resolve("native");
        cleanDirectory(sourceDirectory);
        cleanDirectory(nativeDirectory);
        Files.createDirectories(sourceDirectory);
        Files.writeString(sourceDirectory.resolve("frostjni_runtime.hpp"), runtimeResource.loadHeader(), StandardCharsets.UTF_8);

        Logger.info("[FrostJNI] Native protection enabled");
        hooks.beforeNativeGeneration(request);
        invokeDynamicDesugarer.desugar(request.pool());

        NativeSelector selector = new NativeSelector(toSelectionConfig(config));
        List<String> excludedClasses = new ArrayList<>();
        List<String> conversionFailures = new ArrayList<>();
        List<GeneratedCppClass> generatedClasses = new ArrayList<>();
        List<NativeMethodPlan> nativeMethods = new ArrayList<>();
        JniSymbolRegistry symbolRegistry = new JniSymbolRegistry();

        for (ClassNode classNode : request.pool().getClasses()) {
            ClassModel classModel = parser.parseNode(classNode);
            if (shouldSkipClass(request, classModel, config, excludedClasses)) {
                continue;
            }
            if (isSelectiveWithoutMatch(classModel, config)) {
                continue;
            }
            if (!selector.includeClass(classModel) && config.getIncludeMethods().isEmpty()) {
                continue;
            }

            IRClass irClass = irConverter.convert(classModel);
            IRClass filtered = filterMethods(classModel, irClass, selector, config, conversionFailures);
            if (filtered.methods().isEmpty()) {
                continue;
            }

            GeneratedCppClass generatedClass = cppGenerator.generate(filtered);
            generatedClasses.add(generatedClass);
            Files.writeString(sourceDirectory.resolve(generatedClass.fileName()), generatedClass.source(), StandardCharsets.UTF_8);

            for (IRMethod method : filtered.methods()) {
                NativeMethodPlan plan = new NativeMethodPlan(
                        method.ownerInternalName(),
                        method.name(),
                        method.descriptor(),
                        nameMangler.functionName(method.ownerInternalName(), method.name(), method.descriptor())
                );
                nativeMethods.add(plan);
                symbolRegistry.register(plan.ownerInternalName(), plan.name(), plan.descriptor(), plan.nativeSymbol());
            }
        }

        hooks.afterNativeGeneration(request, generatedClasses);
        if (nativeMethods.isEmpty()) {
            Logger.warn("[FrostJNI] No eligible native methods selected; skipping native compilation");
            return emptyResult(workDirectory, excludedClasses, conversionFailures);
        }

        CompilerInput compilerInput = new CompilerInput(
                sourceDirectory,
                nativeDirectory,
                List.of(TargetPlatform.current()),
                config.getOutputLibraryName(),
                config.isUnityBuild() || "FAST".equalsIgnoreCase(config.getCompileMode()),
                optimizationLevel(config),
                shouldStripSymbols(config)
        );
        hooks.beforeCompilation(compilerInput);
        long compileStart = System.currentTimeMillis();
        CompilationResult compilationResult = buildPipeline.build(compilerInput, symbolRegistry, allowedCompilers(config));
        long compilationTime = System.currentTimeMillis() - compileStart;
        hooks.afterCompilation(compilerInput, compilationResult);

        MethodMappingRegistry registry = MethodMappingRegistry.fromPlans(nativeMethods);
        List<NativeMethodPlan> transformedMethods = patchClasses(request.pool(), registry, config.getOutputLibraryName());
        injectRuntimeClasses(request.processor());
        injectMetadata(request, transformedMethods);
        if (config.isResourceEmbedding()) {
            embedLibraries(request.processor(), compilationResult.libraries());
        } else {
            Logger.warn("[FrostJNI] Resource embedding disabled; native libraries must be distributed separately");
        }

        long sourceBytes = nativeSourceBytes(sourceDirectory);
        Logger.info("[FrostJNI] Converted {} methods in {} classes",
                transformedMethods.size(), transformedMethods.stream().map(NativeMethodPlan::ownerInternalName).distinct().count());

        return new FrostJNIResult(
                (int) transformedMethods.stream().map(NativeMethodPlan::ownerInternalName).distinct().count(),
                transformedMethods.size(),
                sourceBytes,
                compilationTime,
                "auto",
                workDirectory,
                compilationResult.libraries(),
                List.copyOf(excludedClasses),
                List.copyOf(conversionFailures),
                List.copyOf(transformedMethods)
        );
    }

    private IRClass filterMethods(
            ClassModel classModel,
            IRClass irClass,
            NativeSelector selector,
            FrostJNIConfig config,
            List<String> conversionFailures
    ) {
        List<IRMethod> methods = new ArrayList<>();
        for (IRMethod method : irClass.methods()) {
            MethodModel methodModel = findMethod(classModel, method);
            if (methodModel == null || !isPatchEligible(methodModel) || !selector.includeMethod(classModel, methodModel)) {
                continue;
            }
            if (isExcludedMethod(classModel, methodModel, config)) {
                continue;
            }
            Optional<String> unsupportedReason = unsupportedNativeReason(method);
            if (unsupportedReason.isPresent()) {
                conversionFailures.add(classModel.internalName() + "#" + method.name() + method.descriptor()
                        + " uses " + unsupportedReason.get() + " and was left as Java");
                continue;
            }
            methods.add(method);
        }
        return new IRClass(irClass.internalName(), irClass.superName(), irClass.access(), methods);
    }

    private MethodModel findMethod(ClassModel classModel, IRMethod method) {
        for (MethodModel candidate : classModel.methods()) {
            if (candidate.name().equals(method.name()) && candidate.descriptor().equals(method.descriptor())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isPatchEligible(MethodModel method) {
        if ("<init>".equals(method.name()) || "<clinit>".equals(method.name())) {
            return false;
        }
        int hardBlocked = Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE;
        if ((method.access() & hardBlocked) != 0) {
            return false;
        }
        return (method.access() & Opcodes.ACC_SYNTHETIC) == 0 || method.name().startsWith("lambda$");
    }

    private Optional<String> unsupportedNativeReason(IRMethod method) {
        for (IRInstruction instruction : method.instructions()) {
            if (instruction.opcode() == IROpcode.UNKNOWN) {
                String mnemonic = instruction.operands().isEmpty() ? "UNKNOWN" : String.valueOf(instruction.operands().get(0));
                return Optional.of("unsupported JVM opcode " + mnemonic);
            }
            if (instruction.opcode() == IROpcode.INVOKEDYNAMIC
                    && !DynamicInvocationTranslator.isSupported((DynamicInvocationReference) instruction.operands().get(0))) {
                DynamicInvocationReference reference = (DynamicInvocationReference) instruction.operands().get(0);
                return Optional.of("unsupported invokedynamic bootstrap "
                        + reference.bootstrapOwner() + "." + reference.bootstrapName());
            }
        }
        return Optional.empty();
    }

    private boolean isSelectiveWithoutMatch(ClassModel classModel, FrostJNIConfig config) {
        if ("FULL".equalsIgnoreCase(config.getMode())) {
            return false;
        }
        if (explicitlyIncluded(classModel.internalName(), config) || !config.getIncludeMethods().isEmpty()) {
            return false;
        }
        for (String annotation : classModel.annotationDescriptors()) {
            if (containsAnnotation(config.getIncludeAnnotations(), annotation)
                    || "Ldev/frost/obfuscator/jni/loader/Native;".equals(annotation)) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldSkipClass(
            NativeProtectionRequest request,
            ClassModel classModel,
            FrostJNIConfig config,
            List<String> excludedClasses
    ) {
        String internalName = classModel.internalName();
        if (isInternalClass(internalName) || isExcludedClass(classModel, config)) {
            excludedClasses.add(internalName);
            return true;
        }
        String originalName = request.pool().getOriginalName(internalName);
        boolean originalInputClass = request.processor().getOriginalClassBytes().containsKey(originalName)
                || request.processor().getOriginalClassBytes().containsKey(internalName);
        if (!originalInputClass && !explicitlyIncluded(internalName, config)) {
            excludedClasses.add(internalName);
            return true;
        }
        return false;
    }

    private boolean isInternalClass(String internalName) {
        for (String prefix : INTERNAL_PREFIXES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedClass(ClassModel classModel, FrostJNIConfig config) {
        String internalName = classModel.internalName();
        if (containsNormalized(config.getExcludedClasses(), internalName)) {
            return true;
        }
        for (String packageName : config.getExcludedPackages()) {
            String normalized = normalize(packageName);
            if (internalName.equals(normalized) || internalName.startsWith(normalized + "/")) {
                return true;
            }
        }
        for (String annotation : classModel.annotationDescriptors()) {
            if (containsAnnotation(config.getExcludedAnnotations(), annotation)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedMethod(ClassModel classModel, MethodModel methodModel, FrostJNIConfig config) {
        for (String annotation : methodModel.annotationDescriptors()) {
            if (containsAnnotation(config.getExcludedAnnotations(), annotation)) {
                return true;
            }
        }
        return isExcludedClass(classModel, config);
    }

    private boolean explicitlyIncluded(String internalName, FrostJNIConfig config) {
        return containsNormalized(config.getIncludeClasses(), internalName)
                || config.getIncludePackages().stream().map(this::normalize).anyMatch(pkg -> internalName.startsWith(pkg + "/"));
    }

    private List<NativeMethodPlan> patchClasses(ClassPool pool, MethodMappingRegistry registry, String libraryBaseName) {
        List<NativeMethodPlan> transformed = new ArrayList<>();
        for (ClassNode classNode : pool.getClasses()) {
            List<NativeMethodPlan> classMethods = methodTransformer.transform(classNode, registry, libraryBaseName);
            if (!classMethods.isEmpty()) {
                transformed.addAll(classMethods);
                pool.markDirty(classNode.name);
            }
        }
        return transformed;
    }

    private void injectMetadata(NativeProtectionRequest request, List<NativeMethodPlan> transformedMethods) {
        MethodMappingRegistry transformedRegistry = MethodMappingRegistry.fromPlans(transformedMethods);
        PatchPlan plan = new PatchPlan(request.inputPath(), request.config().getOutputLibraryName(), transformedMethods);
        for (Map.Entry<String, byte[]> entry : metadataGenerator.generate(plan, transformedRegistry).entrySet()) {
            request.processor().putResource(entry.getKey(), entry.getValue());
        }
    }

    private void injectRuntimeClasses(JarProcessor processor) throws IOException {
        injectRuntimeClass(processor, NativeLoader.class);
        injectRuntimeClass(processor, LibraryExtractor.class);
        injectRuntimeClass(processor, Native.class);
    }

    private void injectRuntimeClass(JarProcessor processor, Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        try (InputStream inputStream = type.getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("Missing runtime class resource: " + resourceName);
            }
            processor.putResource(resourceName, inputStream.readAllBytes());
        }
    }

    private void embedLibraries(JarProcessor processor, List<NativeLibrary> libraries) throws IOException {
        for (NativeLibrary library : libraries) {
            processor.putResource(library.resourcePath(), Files.readAllBytes(library.path()));
            Logger.info("[PACKAGER] Embedded library {}", library.resourcePath());
        }
    }

    private NativeSelectionConfig toSelectionConfig(FrostJNIConfig config) {
        return new NativeSelectionConfig(
                normalizedSet(config.getIncludePackages()),
                normalizedSet(config.getIncludeClasses()),
                normalizedSet(config.getIncludeMethods()),
                new LinkedHashSet<>(config.getIncludeAnnotations())
        );
    }

    private Set<CompilerKind> allowedCompilers(FrostJNIConfig config) {
        Set<CompilerKind> allowed = new LinkedHashSet<>();
        if (config.isUseClang()) {
            allowed.add(CompilerKind.CLANG);
        }
        if (config.isUseGcc()) {
            allowed.add(CompilerKind.GCC);
        }
        if (config.isUseMsvc()) {
            allowed.add(CompilerKind.MSVC);
        }
        return allowed;
    }

    private String optimizationLevel(FrostJNIConfig config) {
        if ("FAST".equalsIgnoreCase(config.getCompileMode())) {
            return "O0";
        }
        String level = config.getOptimizationLevel();
        return level == null || level.isBlank() ? "O2" : level;
    }

    private boolean shouldStripSymbols(FrostJNIConfig config) {
        return !"FAST".equalsIgnoreCase(config.getCompileMode()) && config.isStripSymbols();
    }

    private Set<String> normalizedSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean containsNormalized(List<String> values, String internalName) {
        for (String value : values) {
            if (normalize(value).equals(internalName)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnnotation(List<String> values, String descriptor) {
        for (String value : values) {
            String normalized = value.startsWith("L") && value.endsWith(";")
                    ? value
                    : "L" + normalize(value) + ";";
            if (normalized.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replace('.', '/');
    }

    private Path resolveWorkDirectory(FrostJNIConfig config, Path outputPath) {
        if (config.getTemporaryDirectory() != null && !config.getTemporaryDirectory().isBlank()) {
            return Path.of(config.getTemporaryDirectory()).toAbsolutePath().normalize();
        }
        Path parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        return parent.resolve(".frostjni-work").normalize();
    }

    private void cleanDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private long nativeSourceBytes(Path sourceDirectory) throws IOException {
        if (!Files.exists(sourceDirectory)) {
            return 0L;
        }
        long total = 0L;
        try (var paths = Files.walk(sourceDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total += Files.size(path);
            }
        }
        return total;
    }

    private FrostJNIResult emptyResult(Path workDirectory, List<String> excludedClasses, List<String> conversionFailures) {
        return new FrostJNIResult(0, 0, 0, 0, "none", workDirectory, List.of(),
                List.copyOf(excludedClasses), List.copyOf(conversionFailures), List.of());
    }
}
