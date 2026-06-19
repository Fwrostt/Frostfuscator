package dev.frost.obfuscator.remapper;

import org.objectweb.asm.commons.Remapper;

public class FrostRemapper extends Remapper {

    private final MappingCollector mappings;

    public FrostRemapper(MappingCollector mappings) {
        this.mappings = mappings;
    }

    @Override
    public String map(String internalName) {
        return mappings.getMappedClass(internalName);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return mappings.getMappedField(owner, name, descriptor);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (name.equals("<init>") || name.equals("<clinit>")) {
            return name;
        }
        return mappings.getMappedMethod(owner, name, descriptor);
    }
}
