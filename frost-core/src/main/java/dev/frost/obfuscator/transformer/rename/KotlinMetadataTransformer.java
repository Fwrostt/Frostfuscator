package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.FrostRemapper;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import kotlin.Metadata;
import kotlin.metadata.*;
import kotlin.metadata.jvm.*;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps Kotlin's protobuf metadata synchronized with JVM class and member mappings. */
public final class KotlinMetadataTransformer extends Transformer {
    private static final String METADATA_DESCRIPTOR = "Lkotlin/Metadata;";

    @Override
    public String getName() {
        return "kotlin-metadata-remap";
    }

    @Override
    public int orderWeight() {
        return 100;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        if (mappings.totalMappings() == 0) return;
        AtomicInteger remapped = new AtomicInteger();
        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) return;
            boolean changed = remapAnnotations(classNode, classNode.visibleAnnotations, mappings)
                    | remapAnnotations(classNode, classNode.invisibleAnnotations, mappings);
            if (changed) {
                pool.markDirty(classNode.name);
                remapped.incrementAndGet();
            }
        });
        if (remapped.get() > 0) log("Remapped Kotlin metadata in {} class(es)", remapped.get());
    }

    private boolean remapAnnotations(ClassNode classNode, List<AnnotationNode> annotations,
                                     MappingCollector mappings) {
        if (annotations == null) return false;
        boolean changed = false;
        for (AnnotationNode annotation : annotations) {
            if (!METADATA_DESCRIPTOR.equals(annotation.desc)) continue;
            try {
                changed |= remapAnnotation(classNode.name, annotation, mappings);
            } catch (RuntimeException failure) {
                Logger.warn("[{}] Could not remap metadata for {}: {}", getName(), classNode.name,
                        failure.getMessage());
            }
        }
        return changed;
    }

    private boolean remapAnnotation(String owner, AnnotationNode annotation, MappingCollector mappings) {
        Map<String, Object> values = annotationValues(annotation);
        Metadata header = JvmMetadataUtil.Metadata(
                integer(values.get("k"), 1),
                integers(values.get("mv")),
                strings(values.get("d1")),
                strings(values.get("d2")),
                string(values.get("xs")),
                string(values.get("pn")),
                integer(values.get("xi"), 0));
        // Writable metadata must be read in strict mode; lenient nodes deliberately reject write().
        KotlinClassMetadata metadata = KotlinClassMetadata.readStrict(header);
        RemapContext context = new RemapContext(owner, mappings);

        if (metadata instanceof KotlinClassMetadata.Class classMetadata) {
            context.remapClass(classMetadata.getKmClass());
        } else if (metadata instanceof KotlinClassMetadata.FileFacade facade) {
            context.remapContainer(facade.getKmPackage());
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart part) {
            context.remapContainer(part.getKmPackage());
            String mappedFacade = context.mapInternal(part.getFacadeClassName());
            if (!mappedFacade.equals(part.getFacadeClassName())) {
                part.setFacadeClassName(mappedFacade);
                context.changed = true;
            }
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassFacade facade) {
            List<String> mappedParts = facade.getPartClassNames().stream().map(context::mapInternal).toList();
            if (!mappedParts.equals(facade.getPartClassNames())) {
                facade.setPartClassNames(mappedParts);
                context.changed = true;
            }
        } else if (metadata instanceof KotlinClassMetadata.SyntheticClass synthetic && synthetic.isLambda()) {
            context.remapFunction(synthetic.getKmLambda().getFunction());
        }

        if (!context.changed) return false;
        Metadata written = metadata.write();
        String packageName = written.pn();
        if (!header.pn().isBlank()) {
            String mappedOwner = mappings.getMappedClass(owner);
            int slash = mappedOwner.lastIndexOf('/');
            packageName = slash < 0 ? "" : mappedOwner.substring(0, slash).replace('/', '.');
        }
        annotation.values = metadataValues(written, packageName);
        return true;
    }

    private static Map<String, Object> annotationValues(AnnotationNode annotation) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (annotation.values == null) return result;
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            result.put(String.valueOf(annotation.values.get(index)), annotation.values.get(index + 1));
        }
        return result;
    }

    private static List<Object> metadataValues(Metadata metadata, String packageName) {
        List<Object> values = new ArrayList<>();
        put(values, "k", metadata.k());
        put(values, "mv", boxed(metadata.mv()));
        put(values, "bv", boxed(metadata.bv()));
        put(values, "d1", new ArrayList<>(Arrays.asList(metadata.d1())));
        put(values, "d2", new ArrayList<>(Arrays.asList(metadata.d2())));
        if (!metadata.xs().isEmpty()) put(values, "xs", metadata.xs());
        if (packageName != null && !packageName.isEmpty()) put(values, "pn", packageName);
        if (metadata.xi() != 0) put(values, "xi", metadata.xi());
        return values;
    }

    private static void put(List<Object> values, String key, Object value) {
        values.add(key);
        values.add(value);
    }

    private static List<Integer> boxed(int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static int[] integers(Object value) {
        if (value instanceof int[] array) return array.clone();
        if (value instanceof List<?> list) return list.stream()
                .filter(Number.class::isInstance).mapToInt(item -> ((Number) item).intValue()).toArray();
        return new int[0];
    }

    private static String[] strings(Object value) {
        if (value instanceof String[] array) return array.clone();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toArray(String[]::new);
        return new String[0];
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static final class RemapContext {
        private final String owner;
        private final MappingCollector mappings;
        private final FrostRemapper remapper;
        private boolean changed;

        private RemapContext(String owner, MappingCollector mappings) {
            this.owner = owner;
            this.mappings = mappings;
            this.remapper = new FrostRemapper(mappings);
        }

        private void remapClass(KmClass kmClass) {
            String mappedName = mapKotlinClassName(kmClass.getName());
            if (!mappedName.equals(kmClass.getName())) {
                kmClass.setName(mappedName);
                changed = true;
            }
            remapTypes(kmClass.getSupertypes());
            remapTypeParameters(kmClass.getTypeParameters());
            remapContainer(kmClass);
            JvmExtensionsKt.getLocalDelegatedProperties(kmClass).forEach(this::remapProperty);
            kmClass.getConstructors().forEach(this::remapConstructor);
            replaceStrings(kmClass.getSealedSubclasses(), this::mapKotlinClassName);
            replaceStrings(kmClass.getNestedClasses(), this::mapNestedClassName);
            if (kmClass.getCompanionObject() != null) {
                String companion = mapNestedClassName(kmClass.getCompanionObject());
                if (!companion.equals(kmClass.getCompanionObject())) {
                    kmClass.setCompanionObject(companion);
                    changed = true;
                }
            }
            for (KmEnumEntry entry : kmClass.getKmEnumEntries()) {
                String mapped = mappings.getMappedField(owner, entry.getName(), "L" + owner + ";");
                if (!mapped.equals(entry.getName())) {
                    entry.setName(mapped);
                    changed = true;
                }
            }
            remapType(kmClass.getInlineClassUnderlyingType());
            remapTypes(kmClass.getContextReceiverTypes());
        }

        private void remapContainer(KmDeclarationContainer container) {
            container.getFunctions().forEach(this::remapFunction);
            container.getProperties().forEach(this::remapProperty);
            container.getTypeAliases().forEach(this::remapTypeAlias);
            if (container instanceof KmPackage kmPackage) {
                JvmExtensionsKt.getLocalDelegatedProperties(kmPackage).forEach(this::remapProperty);
            }
        }

        private void remapFunction(KmFunction function) {
            JvmMethodSignature signature = JvmExtensionsKt.getSignature(function);
            if (signature != null) {
                String mappedName = mappings.getMappedMethod(owner, signature.getName(), signature.getDescriptor());
                String mappedDescriptor = remapper.mapMethodDesc(signature.getDescriptor());
                if (!mappedName.equals(signature.getName()) || !mappedDescriptor.equals(signature.getDescriptor())) {
                    JvmExtensionsKt.setSignature(function, new JvmMethodSignature(mappedName, mappedDescriptor));
                    function.setName(mappedName);
                    changed = true;
                }
            }
            remapType(function.getReturnType());
            remapType(function.getReceiverParameterType());
            remapTypes(function.getContextReceiverTypes());
            remapTypeParameters(function.getTypeParameters());
            function.getValueParameters().forEach(this::remapValueParameter);
            function.getContextParameters().forEach(this::remapValueParameter);
            if (function.getContract() != null) {
                function.getContract().getEffects().forEach(effect -> {
                    effect.getConstructorArguments().forEach(this::remapEffectExpression);
                    remapEffectExpression(effect.getConclusion());
                });
            }
        }

        private void remapProperty(KmProperty property) {
            String mappedPropertyName = property.getName();
            JvmFieldSignature field = JvmExtensionsKt.getFieldSignature(property);
            if (field != null) {
                String mappedName = mappings.getMappedField(owner, field.getName(), field.getDescriptor());
                String mappedDescriptor = remapper.mapDesc(field.getDescriptor());
                JvmExtensionsKt.setFieldSignature(property, new JvmFieldSignature(mappedName, mappedDescriptor));
                mappedPropertyName = mappedName;
                changed |= !mappedName.equals(field.getName()) || !mappedDescriptor.equals(field.getDescriptor());
            }
            if (mappedPropertyName.equals(property.getName())) {
                mappedPropertyName = mappings.getMappedFieldByName(property.getName());
            }
            remapPropertyMethod(property, JvmExtensionsKt.getGetterSignature(property),
                    JvmExtensionsKt::setGetterSignature);
            remapPropertyMethod(property, JvmExtensionsKt.getSetterSignature(property),
                    JvmExtensionsKt::setSetterSignature);
            remapPropertyMethod(property, JvmExtensionsKt.getSyntheticMethodForAnnotations(property),
                    JvmExtensionsKt::setSyntheticMethodForAnnotations);
            remapPropertyMethod(property, JvmExtensionsKt.getSyntheticMethodForDelegate(property),
                    JvmExtensionsKt::setSyntheticMethodForDelegate);
            if (!mappedPropertyName.equals(property.getName())) {
                property.setName(mappedPropertyName);
                changed = true;
            }
            remapType(property.getReturnType());
            remapType(property.getReceiverParameterType());
            remapTypes(property.getContextReceiverTypes());
            remapTypeParameters(property.getTypeParameters());
            property.getContextParameters().forEach(this::remapValueParameter);
            remapValueParameter(property.getSetterParameter());
        }

        private void remapPropertyMethod(KmProperty property, JvmMethodSignature signature,
                                         PropertySignatureSetter setter) {
            if (signature == null) return;
            String mappedName = mappings.getMappedMethod(owner, signature.getName(), signature.getDescriptor());
            String mappedDescriptor = remapper.mapMethodDesc(signature.getDescriptor());
            if (!mappedName.equals(signature.getName()) || !mappedDescriptor.equals(signature.getDescriptor())) {
                setter.set(property, new JvmMethodSignature(mappedName, mappedDescriptor));
                changed = true;
            }
        }

        private void remapConstructor(KmConstructor constructor) {
            JvmMethodSignature signature = JvmExtensionsKt.getSignature(constructor);
            if (signature != null) {
                String mappedDescriptor = remapper.mapMethodDesc(signature.getDescriptor());
                if (!mappedDescriptor.equals(signature.getDescriptor())) {
                    JvmExtensionsKt.setSignature(constructor,
                            new JvmMethodSignature(signature.getName(), mappedDescriptor));
                    changed = true;
                }
            }
            constructor.getValueParameters().forEach(this::remapValueParameter);
        }

        private void remapTypeAlias(KmTypeAlias alias) {
            remapType(alias.getUnderlyingType());
            remapType(alias.getExpandedType());
            remapTypeParameters(alias.getTypeParameters());
        }

        private void remapValueParameter(KmValueParameter parameter) {
            if (parameter == null) return;
            remapType(parameter.getType());
            remapType(parameter.getVarargElementType());
        }

        private void remapTypeParameters(List<KmTypeParameter> parameters) {
            for (KmTypeParameter parameter : parameters) remapTypes(parameter.getUpperBounds());
        }

        private void remapTypes(List<KmType> types) {
            for (KmType type : types) remapType(type);
        }

        private void remapType(KmType type) {
            if (type == null) return;
            KmClassifier classifier = type.getClassifier();
            if (classifier instanceof KmClassifier.Class classClassifier) {
                String mapped = mapKotlinClassName(classClassifier.getName());
                if (!mapped.equals(classClassifier.getName())) {
                    type.setClassifier(new KmClassifier.Class(mapped));
                    changed = true;
                }
            } else if (classifier instanceof KmClassifier.TypeAlias aliasClassifier) {
                String mapped = mapKotlinClassName(aliasClassifier.getName());
                if (!mapped.equals(aliasClassifier.getName())) {
                    type.setClassifier(new KmClassifier.TypeAlias(mapped));
                    changed = true;
                }
            }
            for (KmTypeProjection argument : type.getArguments()) remapType(argument.getType());
            remapType(type.getAbbreviatedType());
            remapType(type.getOuterType());
            if (type.getFlexibleTypeUpperBound() != null) {
                remapType(type.getFlexibleTypeUpperBound().getType());
            }
        }

        private void remapEffectExpression(KmEffectExpression expression) {
            if (expression == null) return;
            remapType(expression.isInstanceType());
            expression.getAndArguments().forEach(this::remapEffectExpression);
            expression.getOrArguments().forEach(this::remapEffectExpression);
        }

        private String mapNestedClassName(String nestedName) {
            String mapped = mappings.getMappedClass(owner + "$" + nestedName.replace('.', '$'));
            if (mapped.equals(owner + "$" + nestedName.replace('.', '$'))) return nestedName;
            int slash = mapped.lastIndexOf('/');
            String simpleName = mapped.substring(slash + 1);
            int nested = simpleName.lastIndexOf('$');
            return nested < 0 ? simpleName : simpleName.substring(nested + 1);
        }

        private String mapKotlinClassName(String kotlinName) {
            return toKotlinClassName(mapInternal(JvmMetadataUtil.toJvmInternalName(kotlinName)));
        }

        private String mapInternal(String internalName) {
            return mappings.getMappedClass(internalName);
        }

        private static String toKotlinClassName(String internalName) {
            int slash = internalName.lastIndexOf('/');
            String packageName = slash < 0 ? "" : internalName.substring(0, slash + 1);
            String className = internalName.substring(slash + 1).replace('$', '.');
            return packageName + className;
        }

        private void replaceStrings(List<String> values, java.util.function.UnaryOperator<String> mapper) {
            for (int index = 0; index < values.size(); index++) {
                String mapped = mapper.apply(values.get(index));
                if (!mapped.equals(values.get(index))) {
                    values.set(index, mapped);
                    changed = true;
                }
            }
        }
    }

    @FunctionalInterface
    private interface PropertySignatureSetter {
        void set(KmProperty property, JvmMethodSignature signature);
    }
}
