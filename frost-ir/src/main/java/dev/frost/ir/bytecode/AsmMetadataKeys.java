package dev.frost.ir.bytecode;

import dev.frost.ir.core.MetadataKey;

public final class AsmMetadataKeys {
    private AsmMetadataKeys() {}
    public static final MetadataKey<Long> INSTRUCTION_INDEX = MetadataKey.persistentKey("frost.asm", "instruction-index", Long.class);
    public static final MetadataKey<Long> OPCODE = MetadataKey.persistentKey("frost.asm", "opcode", Long.class);
    public static final MetadataKey<String> OPCODE_NAME = MetadataKey.persistentKey("frost.asm", "opcode-name", String.class);
    public static final MetadataKey<Long> LINE_NUMBER = MetadataKey.persistentKey("frost.asm", "line-number", Long.class);
    public static final MetadataKey<Long> BLOCK_START = MetadataKey.persistentKey("frost.asm", "block-start", Long.class);
    public static final MetadataKey<Long> BLOCK_END = MetadataKey.persistentKey("frost.asm", "block-end", Long.class);
    public static final MetadataKey<String> PHI_SLOT_KIND = MetadataKey.persistentKey("frost.asm", "phi-slot-kind", String.class);
    public static final MetadataKey<Long> PHI_SLOT_INDEX = MetadataKey.persistentKey("frost.asm", "phi-slot-index", Long.class);
}
