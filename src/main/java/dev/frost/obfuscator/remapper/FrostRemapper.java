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
    public Object mapValue(Object value) {
        if (value instanceof String text) {
            String mapped = mapClassString(text);
            if (mapped != null) {
                return mapped;
            }
        }
        return super.mapValue(value);
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

    private String mapClassString(String value) {
        if (value.isBlank()) {
            return null;
        }

        String direct = mappings.getMappedClass(value);
        if (!direct.equals(value)) {
            return direct;
        }

        String internal = value.replace('.', '/');
        String mapped = mappings.getMappedClass(internal);
        if (!mapped.equals(internal)) {
            return value.indexOf('.') >= 0 ? mapped.replace('/', '.') : mapped;
        }

        return null;
    }
}
