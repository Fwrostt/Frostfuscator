package dev.frost.obfuscator.transformer.watermark;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

public class WatermarkTransformer extends Transformer {

    @Override
    public String getName() {
        return "watermark";
    }

    @Override
    public String getCategory() {
        return "Ownership";
    }

    @Override
    public void transform(Context context) {
        String id = context.config().getOption("id", UUID.randomUUID().toString());
        String owner = context.config().getOption("owner", "unknown");
        String fieldName = context.config().getOption("field-name", "__frost$watermark");
        boolean classAnnotations = getBooleanOption(context, "class-annotations", true);
        boolean stringField = getBooleanOption(context, "string-field", true);

        LongAdder touched = new LongAdder();
        context.pool().forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, context.config(), context.pool().getGlobalExclusions(), context.pool().getGlobalInclusions())) {
                return;
            }

            if (classAnnotations) {
                if (classNode.invisibleAnnotations == null) {
                    classNode.invisibleAnnotations = new ArrayList<>();
                }
                AnnotationNode mark = new AnnotationNode("Ldev/frost/watermark/Owner;");
                mark.values = new ArrayList<>();
                mark.values.add("id");
                mark.values.add(id);
                mark.values.add("owner");
                mark.values.add(owner);
                classNode.invisibleAnnotations.add(mark);
            }

            if (stringField && (classNode.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) == 0
                    && classNode.fields.stream().noneMatch(f -> fieldName.equals(f.name))) {
                classNode.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                        fieldName,
                        "Ljava/lang/String;",
                        null,
                        owner + ":" + id
                ));
            }

            context.pool().markDirty(classNode.name);
            touched.increment();
        });

        String record = "owner=" + owner + "\nid=" + id + "\ngenerated=" + Instant.now() + "\nclasses=" + touched.sum() + "\n";
        context.jar().putResource("META-INF/frostfuscator/watermark.properties", record.getBytes(StandardCharsets.UTF_8));
        context.stats().add("watermarkedClasses", touched.sum());
        log("Watermarked {} classes", touched.sum());
    }

    private boolean getBooleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
