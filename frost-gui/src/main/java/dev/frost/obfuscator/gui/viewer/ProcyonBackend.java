package dev.frost.obfuscator.gui.viewer;

import com.strobel.assembler.metadata.ClasspathTypeLoader;
import com.strobel.assembler.metadata.CompositeTypeLoader;
import com.strobel.assembler.metadata.JarTypeLoader;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;

import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class ProcyonBackend implements DecompilerBackend {
    @Override
    public String id() {
        return "procyon";
    }

    @Override
    public String displayName() {
        return "Procyon";
    }

    @Override
    public String version() {
        return "0.6.0";
    }

    @Override
    public DecompileResult decompile(Path archive, String classEntry) throws Exception {
        Instant started = Instant.now();
        List<String> diagnostics = new ArrayList<>();

        String internalName = classEntry.substring(0, classEntry.length() - ".class".length());

        DecompilerSettings settings = DecompilerSettings.javaDefaults();
        settings.setShowSyntheticMembers(false);
        settings.setSimplifyMemberReferences(true);
        settings.setMergeVariables(true);
        settings.setRetainRedundantCasts(false);
        settings.setRetainPointlessSwitches(false);
        settings.setIncludeErrorDiagnostics(true);

        try (JarFile jarFile = new JarFile(archive.toFile())) {
            settings.setTypeLoader(new CompositeTypeLoader(
                    new JarTypeLoader(jarFile),
                    new ClasspathTypeLoader()
            ));

            StringWriter writer = new StringWriter();
            PlainTextOutput output = new PlainTextOutput(writer);
            Decompiler.decompile(internalName, output, settings);

            String result = writer.toString().trim();
            if (result.isEmpty()) {
                throw new IllegalStateException("Procyon did not produce source for " + classEntry);
            }
            return new DecompileResult(result, Duration.between(started, Instant.now()), diagnostics);
        }
    }
}
