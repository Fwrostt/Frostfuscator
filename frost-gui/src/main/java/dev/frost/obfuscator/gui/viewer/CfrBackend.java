package dev.frost.obfuscator.gui.viewer;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class CfrBackend implements DecompilerBackend {
    @Override
    public String id() {
        return "cfr";
    }

    @Override
    public String displayName() {
        return "CFR";
    }

    @Override
    public String version() {
        return "0.152";
    }

    @Override
    public DecompileResult decompile(Path archive, String classEntry) throws Exception {
        Instant started = Instant.now();
        List<String> diagnostics = new ArrayList<>();
        StringBuilder code = new StringBuilder();

        Map<String, String> options = new HashMap<>();
        options.put("extraclasspath", archive.toAbsolutePath().toString());
        options.put("showversion", "false");
        options.put("comments", "true");
        options.put("hidebridgemethods", "true");
        options.put("hidesynthetics", "true");
        options.put("decodestringswitch", "true");
        options.put("decodeenumswitch", "true");
        options.put("decodelambdas", "true");
        options.put("sugarasserts", "true");
        options.put("sugarboxedsorting", "true");
        options.put("removebadgenerics", "true");
        options.put("removebooleanequalities", "true");
        options.put("ignoreexceptionsonerr", "true");
        options.put("renameillegalidents", "true");
        options.put("renamesmallmembers", "1");
        options.put("previewfeatures", "true");

        String internalName = classEntry.substring(0, classEntry.length() - ".class".length());
        String targetClassName = internalName.replace('/', '.');

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                if (sinkType == SinkType.JAVA) {
                    if (available.contains(SinkClass.DECOMPILED)) {
                        return List.of(SinkClass.DECOMPILED);
                    }
                    return List.of(SinkClass.STRING);
                }
                return List.of(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA) {
                    if (sinkClass == SinkClass.DECOMPILED) {
                        return item -> {
                            if (item instanceof SinkReturns.Decompiled decompiled) {
                                String fullClassName = decompiled.getPackageName() == null || decompiled.getPackageName().isBlank()
                                        ? decompiled.getClassName()
                                        : decompiled.getPackageName() + "." + decompiled.getClassName();
                                if (fullClassName.equals(targetClassName) || targetClassName.startsWith(fullClassName + "$")) {
                                    if (!code.isEmpty()) {
                                        code.append("\n\n");
                                    }
                                    code.append(decompiled.getJava());
                                }
                            }
                        };
                    }
                    return item -> code.append(item);
                }
                if (sinkType == SinkType.PROGRESS || sinkType == SinkType.EXCEPTION) {
                    return item -> diagnostics.add(String.valueOf(item));
                }
                return item -> {};
            }
        };

        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(sinkFactory)
                .build();

        // Pass only the class name to analyse so CFR loads it from extraclasspath and decompiles only that class
        driver.analyse(List.of(targetClassName));

        String result = code.toString().trim();
        if (result.isEmpty()) {
            // Fallback: analyse the archive path directly if class name resolution failed
            CfrDriver archiveDriver = new CfrDriver.Builder()
                    .withOptions(options)
                    .withOutputSink(sinkFactory)
                    .build();
            archiveDriver.analyse(List.of(archive.toAbsolutePath().toString()));
            result = code.toString().trim();
        }

        if (result.isEmpty()) {
            throw new IllegalStateException("CFR did not produce source for " + classEntry
                    + (diagnostics.isEmpty() ? "" : ": " + diagnostics.get(diagnostics.size() - 1)));
        }
        return new DecompileResult(result, Duration.between(started, Instant.now()), diagnostics);
    }
}
