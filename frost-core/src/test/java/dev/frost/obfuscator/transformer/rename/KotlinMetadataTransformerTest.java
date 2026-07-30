package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import kotlin.Metadata;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmFunction;
import kotlin.metadata.KmType;
import kotlin.metadata.KmValueParameter;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmMetadataUtil;
import kotlin.metadata.jvm.JvmMetadataVersion;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class KotlinMetadataTransformerTest {

    @Test
    void synchronizesKotlinClassFunctionAndTypeNames() {
        KmClass kmClass = new KmClass();
        kmClass.setName("example/Sample");
        KmFunction function = new KmFunction("greet");
        function.setReturnType(classType("example/Dependency"));
        KmValueParameter parameter = new KmValueParameter("dependency");
        parameter.setType(classType("example/Dependency"));
        function.getValueParameters().add(parameter);
        JvmExtensionsKt.setSignature(function,
                new JvmMethodSignature("greet", "(Lexample/Dependency;)Lexample/Dependency;"));
        kmClass.getFunctions().add(function);

        Metadata metadata = new KotlinClassMetadata.Class(kmClass,
                new JvmMetadataVersion(2, 4, 0), 0).write();
        ClassNode node = new ClassNode();
        node.name = "example/Sample";
        node.superName = "java/lang/Object";
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.visibleAnnotations = List.of(toAnnotation(metadata));

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        MappingCollector mappings = new MappingCollector();
        mappings.mapClass("example/Sample", "obfuscated/A");
        mappings.mapClass("example/Dependency", "obfuscated/B");
        mappings.mapMethod("example/Sample", "greet",
                "(Lexample/Dependency;)Lexample/Dependency;", "c");

        new KotlinMetadataTransformer().transform(pool, mappings, new TransformerConfig());

        KotlinClassMetadata.Class rewritten = assertInstanceOf(KotlinClassMetadata.Class.class,
                KotlinClassMetadata.readLenient(fromAnnotation(node.visibleAnnotations.getFirst())));
        KmClass result = rewritten.getKmClass();
        assertEquals("obfuscated/A", result.getName());
        KmFunction mappedFunction = result.getFunctions().getFirst();
        assertEquals("c", mappedFunction.getName());
        JvmMethodSignature signature = JvmExtensionsKt.getSignature(mappedFunction);
        assertEquals("c", signature.getName());
        assertEquals("(Lobfuscated/B;)Lobfuscated/B;", signature.getDescriptor());
        assertEquals("obfuscated/B",
                ((KmClassifier.Class) mappedFunction.getReturnType().getClassifier()).getName());
        assertEquals("obfuscated/B",
                ((KmClassifier.Class) mappedFunction.getValueParameters().getFirst()
                        .getType().getClassifier()).getName());
    }

    private static KmType classType(String name) {
        KmType type = new KmType();
        type.setClassifier(new KmClassifier.Class(name));
        return type;
    }

    private static AnnotationNode toAnnotation(Metadata metadata) {
        AnnotationNode annotation = new AnnotationNode("Lkotlin/Metadata;");
        annotation.values = new ArrayList<>();
        put(annotation, "k", metadata.k());
        put(annotation, "mv", boxed(metadata.mv()));
        put(annotation, "d1", new ArrayList<>(Arrays.asList(metadata.d1())));
        put(annotation, "d2", new ArrayList<>(Arrays.asList(metadata.d2())));
        put(annotation, "xi", metadata.xi());
        return annotation;
    }

    private static Metadata fromAnnotation(AnnotationNode annotation) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < annotation.values.size(); index += 2) {
            values.put(String.valueOf(annotation.values.get(index)), annotation.values.get(index + 1));
        }
        return JvmMetadataUtil.Metadata(
                ((Number) values.get("k")).intValue(),
                ((List<?>) values.get("mv")).stream().mapToInt(value -> ((Number) value).intValue()).toArray(),
                ((List<?>) values.get("d1")).stream().map(String::valueOf).toArray(String[]::new),
                ((List<?>) values.get("d2")).stream().map(String::valueOf).toArray(String[]::new),
                String.valueOf(values.getOrDefault("xs", "")),
                String.valueOf(values.getOrDefault("pn", "")),
                ((Number) values.getOrDefault("xi", 0)).intValue());
    }

    private static void put(AnnotationNode annotation, String name, Object value) {
        annotation.values.add(name);
        annotation.values.add(value);
    }

    private static List<Integer> boxed(int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }
}
