package dev.frost.loader;

public class FrostVM {
    // Internal Opcodes
    private static final int OP_NOP = 0;
    private static final int OP_ACONST_NULL = 1;
    private static final int OP_ICONST_M1 = 2;
    private static final int OP_ICONST_0 = 3;
    private static final int OP_ICONST_1 = 4;
    private static final int OP_ICONST_2 = 5;
    private static final int OP_ICONST_3 = 6;
    private static final int OP_ICONST_4 = 7;
    private static final int OP_ICONST_5 = 8;
    private static final int OP_LCONST_0 = 9;
    private static final int OP_LCONST_1 = 10;
    private static final int OP_FCONST_0 = 11;
    private static final int OP_FCONST_1 = 12;
    private static final int OP_FCONST_2 = 13;
    private static final int OP_DCONST_0 = 14;
    private static final int OP_DCONST_1 = 15;
    private static final int OP_BIPUSH = 16;
    private static final int OP_SIPUSH = 17;
    private static final int OP_LDC = 18;
    private static final int OP_LOAD = 19;
    private static final int OP_STORE = 20;
    private static final int OP_IALOAD = 21;
    private static final int OP_LALOAD = 22;
    private static final int OP_FALOAD = 23;
    private static final int OP_DALOAD = 24;
    private static final int OP_AALOAD = 25;
    private static final int OP_BALOAD = 26;
    private static final int OP_CALOAD = 27;
    private static final int OP_SALOAD = 28;
    private static final int OP_IASTORE = 29;
    private static final int OP_LASTORE = 30;
    private static final int OP_FASTORE = 31;
    private static final int OP_DASTORE = 32;
    private static final int OP_AASTORE = 33;
    private static final int OP_BASTORE = 34;
    private static final int OP_CASTORE = 35;
    private static final int OP_SASTORE = 36;
    private static final int OP_POP = 37;
    private static final int OP_POP2 = 38;
    private static final int OP_DUP = 39;
    private static final int OP_DUP_X1 = 40;
    private static final int OP_DUP_X2 = 41;
    private static final int OP_DUP2 = 42;
    private static final int OP_DUP2_X1 = 43;
    private static final int OP_DUP2_X2 = 44;
    private static final int OP_SWAP = 45;
    private static final int OP_IADD = 46;
    private static final int OP_LADD = 47;
    private static final int OP_FADD = 48;
    private static final int OP_DADD = 49;
    private static final int OP_ISUB = 50;
    private static final int OP_LSUB = 51;
    private static final int OP_FSUB = 52;
    private static final int OP_DSUB = 53;
    private static final int OP_IMUL = 54;
    private static final int OP_LMUL = 55;
    private static final int OP_FMUL = 56;
    private static final int OP_DMUL = 57;
    private static final int OP_IDIV = 58;
    private static final int OP_LDIV = 59;
    private static final int OP_FDIV = 60;
    private static final int OP_DDIV = 61;
    private static final int OP_IREM = 62;
    private static final int OP_LREM = 63;
    private static final int OP_FREM = 64;
    private static final int OP_DREM = 65;
    private static final int OP_INEG = 66;
    private static final int OP_LNEG = 67;
    private static final int OP_FNEG = 68;
    private static final int OP_DNEG = 69;
    private static final int OP_ISHL = 70;
    private static final int OP_LSHL = 71;
    private static final int OP_ISHR = 72;
    private static final int OP_LSHR = 73;
    private static final int OP_IUSHR = 74;
    private static final int OP_LUSHR = 75;
    private static final int OP_IAND = 76;
    private static final int OP_LAND = 77;
    private static final int OP_IOR = 78;
    private static final int OP_LOR = 79;
    private static final int OP_IXOR = 80;
    private static final int OP_LXOR = 81;
    private static final int OP_IINC = 82;
    private static final int OP_I2L = 83;
    private static final int OP_I2F = 84;
    private static final int OP_I2D = 85;
    private static final int OP_L2I = 86;
    private static final int OP_L2F = 87;
    private static final int OP_L2D = 88;
    private static final int OP_F2I = 89;
    private static final int OP_F2L = 90;
    private static final int OP_F2D = 91;
    private static final int OP_D2I = 92;
    private static final int OP_D2L = 93;
    private static final int OP_D2F = 94;
    private static final int OP_I2B = 95;
    private static final int OP_I2C = 96;
    private static final int OP_I2S = 97;
    private static final int OP_LCMP = 98;
    private static final int OP_FCMPL = 99;
    private static final int OP_FCMPG = 100;
    private static final int OP_DCMPL = 101;
    private static final int OP_DCMPG = 102;
    private static final int OP_IFEQ = 103;
    private static final int OP_IFNE = 104;
    private static final int OP_IFLT = 105;
    private static final int OP_IFGE = 106;
    private static final int OP_IFGT = 107;
    private static final int OP_IFLE = 108;
    private static final int OP_IF_ICMPEQ = 109;
    private static final int OP_IF_ICMPNE = 110;
    private static final int OP_IF_ICMPLT = 111;
    private static final int OP_IF_ICMPGE = 112;
    private static final int OP_IF_ICMPGT = 113;
    private static final int OP_IF_ICMPLE = 114;
    private static final int OP_IF_ACMPEQ = 115;
    private static final int OP_IF_ACMPNE = 116;
    private static final int OP_GOTO = 117;
    private static final int OP_TABLESWITCH = 118;
    private static final int OP_LOOKUPSWITCH = 119;
    private static final int OP_IRETURN = 120;
    private static final int OP_LRETURN = 121;
    private static final int OP_FRETURN = 122;
    private static final int OP_DRETURN = 123;
    private static final int OP_ARETURN = 124;
    private static final int OP_RETURN = 125;
    private static final int OP_GETSTATIC = 126;
    private static final int OP_PUTSTATIC = 127;
    private static final int OP_GETFIELD = 128;
    private static final int OP_PUTFIELD = 129;
    private static final int OP_INVOKEVIRTUAL = 130;
    private static final int OP_INVOKESPECIAL = 131;
    private static final int OP_INVOKESTATIC = 132;
    private static final int OP_INVOKEINTERFACE = 133;
    private static final int OP_NEW = 134;
    private static final int OP_NEWARRAY = 135;
    private static final int OP_ANEWARRAY = 136;
    private static final int OP_ARRAYLENGTH = 137;
    private static final int OP_ATHROW = 138;
    private static final int OP_CHECKCAST = 139;
    private static final int OP_INSTANCEOF = 140;
    private static final int OP_MONITORENTER = 141;
    private static final int OP_MONITOREXIT = 142;
    private static final int OP_IFNULL = 143;
    private static final int OP_IFNONNULL = 144;
    private static final int OP_TRAP = 145;
    private static final int OP_LOAD2 = 146;
    private static final int OP_STORE2 = 147;

    private static final int[] customToInternalOpcodeTable = new int[256];
    private static String OP_TABLE_STR = "OP_TABLE_PLACEHOLDER";
    static {

        if (OP_TABLE_STR.contains(",")) {
            String[] parts = OP_TABLE_STR.split(",");
            for (int i = 0; i < 256; i++) {
                customToInternalOpcodeTable[i] = Integer.parseInt(parts[i]);
            }
        } else {
            for (int i = 0; i < 256; i++) {
                customToInternalOpcodeTable[i] = i <= 147 ? i : 145;
            }
        }
    }

    public static class ClassRef {
        public final String name;
        public ClassRef(String name) {
            this.name = name;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClassRef)) return false;
            ClassRef classRef = (ClassRef) o;
            return name.equals(classRef.name);
        }
        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    public static class FieldRef {
        public final String className;
        public final String name;
        public final String desc;
        public FieldRef(String className, String name, String desc) {
            this.className = className;
            this.name = name;
            this.desc = desc;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldRef)) return false;
            FieldRef fieldRef = (FieldRef) o;
            return className.equals(fieldRef.className) && name.equals(fieldRef.name) && desc.equals(fieldRef.desc);
        }
        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + name.hashCode();
            result = 31 * result + desc.hashCode();
            return result;
        }
    }

    public static class MethodRef {
        public final String className;
        public final String name;
        public final String desc;
        public final boolean isInterface;
        public MethodRef(String className, String name, String desc, boolean isInterface) {
            this.className = className;
            this.name = name;
            this.desc = desc;
            this.isInterface = isInterface;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodRef)) return false;
            MethodRef methodRef = (MethodRef) o;
            return isInterface == methodRef.isInterface && className.equals(methodRef.className) && name.equals(methodRef.name) && desc.equals(methodRef.desc);
        }
        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + name.hashCode();
            result = 31 * result + desc.hashCode();
            result = 31 * result + (isInterface ? 1 : 0);
            return result;
        }
    }

    private static class NewPlaceholder {
        final Class<?> clazz;
        final int id;
        NewPlaceholder(Class<?> clazz, int id) {
            this.clazz = clazz;
            this.id = id;
        }
    }

    private static int getSlotSize(Class<?> type) {
        return (type == long.class || type == double.class) ? 2 : 1;
    }

    private static void pushValue(Object[] stack, int sp, Object val, Class<?> type) {
        if (type == long.class || type == double.class) {
            stack[sp] = val;
            stack[sp+1] = null;
        } else {
            stack[sp] = val;
        }
    }

    private static Object coerce(Object val, Class<?> type) {
        if (val == null) return null;
        if (type.isPrimitive()) {
            if (type == int.class) return ((Number) val).intValue();
            if (type == long.class) return ((Number) val).longValue();
            if (type == float.class) return ((Number) val).floatValue();
            if (type == double.class) return ((Number) val).doubleValue();
            if (type == byte.class) return ((Number) val).byteValue();
            if (type == short.class) return ((Number) val).shortValue();
            if (type == char.class) {
                if (val instanceof Character) return val;
                return (char) ((Number) val).intValue();
            }
            if (type == boolean.class) {
                if (val instanceof Boolean) return val;
                return ((Number) val).intValue() != 0;
            }
        }
        return val;
    }

    private static int readInt(byte[] bytecode, int pc) {
        return ((bytecode[pc] & 0xFF) << 24) |
               ((bytecode[pc+1] & 0xFF) << 16) |
               ((bytecode[pc+2] & 0xFF) << 8) |
               (bytecode[pc+3] & 0xFF);
    }

    private static ClassLoader resolveLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : FrostVM.class.getClassLoader();
    }

    private static Class<?> loadClass(String internalName) throws ClassNotFoundException {
        String name = internalName.replace('/', '.');
        try {
            return Class.forName(name, true, resolveLoader());
        } catch (ClassNotFoundException exception) {
            return Class.forName(name, true, FrostVM.class.getClassLoader());
        }
    }

    private static java.lang.reflect.Field resolveField(FieldRef ref) throws Exception {
        Class<?> clazz = loadClass(ref.className);
        Class<?> current = clazz;
        while (current != null) {
            try {
                java.lang.reflect.Field f = current.getDeclaredField(ref.name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(ref.className + "." + ref.name);
    }

    private static java.lang.reflect.Method resolveMethod(MethodRef ref, Class<?>[] paramTypes) throws Exception {
        Class<?> clazz = loadClass(ref.className);
        Class<?> current = clazz;
        while (current != null) {
            try {
                java.lang.reflect.Method m = current.getDeclaredMethod(ref.name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            try {
                java.lang.reflect.Method m = iface.getDeclaredMethod(ref.name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(ref.className + "." + ref.name + ref.desc);
    }

    private static Class<?>[] parseDesc(String desc, ClassLoader cl) throws Exception {
        java.util.List<Class<?>> types = new java.util.ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'B') { types.add(byte.class); i++; }
            else if (c == 'C') { types.add(char.class); i++; }
            else if (c == 'D') { types.add(double.class); i++; }
            else if (c == 'F') { types.add(float.class); i++; }
            else if (c == 'I') { types.add(int.class); i++; }
            else if (c == 'J') { types.add(long.class); i++; }
            else if (c == 'S') { types.add(short.class); i++; }
            else if (c == 'Z') { types.add(boolean.class); i++; }
            else if (c == 'V') { types.add(void.class); i++; }
            else if (c == '[') {
                int dimensions = 0;
                while (desc.charAt(i) == '[') {
                    dimensions++;
                    i++;
                }
                Class<?> base;
                char baseChar = desc.charAt(i);
                if (baseChar == 'L') {
                    int end = desc.indexOf(';', i);
                    String className = desc.substring(i + 1, end).replace('/', '.');
                    base = Class.forName(className, true, cl);
                    i = end + 1;
                } else {
                    if (baseChar == 'B') base = byte.class;
                    else if (baseChar == 'C') base = char.class;
                    else if (baseChar == 'D') base = double.class;
                    else if (baseChar == 'F') base = float.class;
                    else if (baseChar == 'I') base = int.class;
                    else if (baseChar == 'J') base = long.class;
                    else if (baseChar == 'S') base = short.class;
                    else if (baseChar == 'Z') base = boolean.class;
                    else throw new IllegalArgumentException("Unknown array base: " + baseChar);
                    i++;
                }
                int[] dims = new int[dimensions];
                base = java.lang.reflect.Array.newInstance(base, dims).getClass();
                types.add(base);
            }
            else if (c == 'L') {
                int end = desc.indexOf(';', i);
                String className = desc.substring(i + 1, end).replace('/', '.');
                types.add(Class.forName(className, true, cl));
                i = end + 1;
            }
        }
        return types.toArray(new Class<?>[0]);
    }

    private static Class<?> parseReturnType(String desc, ClassLoader cl) throws Exception {
        int i = desc.indexOf(')') + 1;
        char c = desc.charAt(i);
        if (c == 'B') return byte.class;
        if (c == 'C') return char.class;
        if (c == 'D') return double.class;
        if (c == 'F') return float.class;
        if (c == 'I') return int.class;
        if (c == 'J') return long.class;
        if (c == 'S') return short.class;
        if (c == 'Z') return boolean.class;
        if (c == 'V') return void.class;
        if (c == '[') {
            int dimensions = 0;
            while (desc.charAt(i) == '[') {
                dimensions++;
                i++;
            }
            Class<?> base;
            char baseChar = desc.charAt(i);
            if (baseChar == 'L') {
                int end = desc.indexOf(';', i);
                String className = desc.substring(i + 1, end).replace('/', '.');
                base = Class.forName(className, true, cl);
            } else {
                if (baseChar == 'B') base = byte.class;
                else if (baseChar == 'C') base = char.class;
                else if (baseChar == 'D') base = double.class;
                else if (baseChar == 'F') base = float.class;
                else if (baseChar == 'I') base = int.class;
                else if (baseChar == 'J') base = long.class;
                else if (baseChar == 'S') base = short.class;
                else if (baseChar == 'Z') base = boolean.class;
                else throw new IllegalArgumentException("Unknown array base: " + baseChar);
            }
            int[] dims = new int[dimensions];
            return java.lang.reflect.Array.newInstance(base, dims).getClass();
        }
        if (c == 'L') {
            int end = desc.indexOf(';', i);
            String className = desc.substring(i + 1, end).replace('/', '.');
            return Class.forName(className, true, cl);
        }
        throw new IllegalArgumentException("Invalid return type in desc: " + desc);
    }

    private static Object[] popArgs(Object[] stack, int[] spPtr, Class<?>[] paramTypes) {
        int sp = spPtr[0];
        Object[] args = new Object[paramTypes.length];
        for (int i = paramTypes.length - 1; i >= 0; i--) {
            Class<?> pType = paramTypes[i];
            int size = getSlotSize(pType);
            sp -= size;
            args[i] = coerce(stack[sp], pType);
        }
        spPtr[0] = sp;
        return args;
    }

    private static Object invokeSpecialMethod(MethodRef ref, Object receiver, Object[] args, Class<?>[] paramTypes) throws Throwable {
        Class<?> clazz = loadClass(ref.className);
        java.lang.invoke.MethodType mt = java.lang.invoke.MethodType.methodType(
            parseReturnType(ref.desc, resolveLoader()),
            paramTypes
        );
        Class<?> caller = receiver.getClass();
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(caller, java.lang.invoke.MethodHandles.lookup());
        java.lang.invoke.MethodHandle mh = lookup.findSpecial(clazz, ref.name, mt, caller);
        return mh.bindTo(receiver).invokeWithArguments(args);
    }

    public static byte[] decodeBytecode(String encoded, int key) {
        byte[] bytecode = java.util.Base64.getDecoder().decode(encoded);
        int state = key ^ 0x6d2b79f5;
        for (int i = 0; i < bytecode.length; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            bytecode[i] = (byte) (bytecode[i] ^ state);
        }
        return bytecode;
    }

    public static Object execute(
        byte[] bytecode,
        Object[] constPool,
        Object[] args,
        int[] argSlots,
        int maxLocals,
        int maxStack
    ) throws Throwable {
        Object[] stack = new Object[Math.max(64, maxStack * 4 + 32)];
        int sp = 0;
        Object[] locals = new Object[maxLocals];
        if (args != null && argSlots != null) {
            for (int i = 0; i < args.length; i++) {
                locals[argSlots[i]] = args[i];
            }
        }

        int pc = 0;
        int nextPlaceholderId = 0;

        while (pc < bytecode.length) {
            int customOp = bytecode[pc++] & 0xFF;
            int op = customToInternalOpcodeTable[customOp];

            switch (op) {
                case OP_NOP:
                    break;
                case OP_ACONST_NULL:
                    stack[sp++] = null;
                    break;
                case OP_ICONST_M1:
                    stack[sp++] = -1;
                    break;
                case OP_ICONST_0:
                    stack[sp++] = 0;
                    break;
                case OP_ICONST_1:
                    stack[sp++] = 1;
                    break;
                case OP_ICONST_2:
                    stack[sp++] = 2;
                    break;
                case OP_ICONST_3:
                    stack[sp++] = 3;
                    break;
                case OP_ICONST_4:
                    stack[sp++] = 4;
                    break;
                case OP_ICONST_5:
                    stack[sp++] = 5;
                    break;
                case OP_LCONST_0:
                    stack[sp++] = 0L;
                    stack[sp++] = null;
                    break;
                case OP_LCONST_1:
                    stack[sp++] = 1L;
                    stack[sp++] = null;
                    break;
                case OP_FCONST_0:
                    stack[sp++] = 0.0f;
                    break;
                case OP_FCONST_1:
                    stack[sp++] = 1.0f;
                    break;
                case OP_FCONST_2:
                    stack[sp++] = 2.0f;
                    break;
                case OP_DCONST_0:
                    stack[sp++] = 0.0d;
                    stack[sp++] = null;
                    break;
                case OP_DCONST_1:
                    stack[sp++] = 1.0d;
                    stack[sp++] = null;
                    break;
                case OP_BIPUSH:
                    stack[sp++] = (int) bytecode[pc++];
                    break;
                case OP_SIPUSH:
                    stack[sp++] = (int) (short) (((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF));
                    break;
                case OP_LDC: {
                    int idx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    Object val = constPool[idx];
                    if (val instanceof ClassRef) {
                        val = loadClass(((ClassRef) val).name);
                    }
                    if (val instanceof Long || val instanceof Double) {
                        stack[sp++] = val;
                        stack[sp++] = null;
                    } else {
                        stack[sp++] = val;
                    }
                    break;
                }
                case OP_LOAD: {
                    int idx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    stack[sp++] = locals[idx];
                    break;
                }
                case OP_LOAD2: {
                    int idx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    stack[sp++] = locals[idx];
                    stack[sp++] = null;
                    break;
                }
                case OP_STORE: {
                    int idx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    locals[idx] = stack[--sp];
                    break;
                }
                case OP_STORE2: {
                    int idx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    sp -= 2;
                    locals[idx] = stack[sp];
                    break;
                }
                case OP_IALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    int[] arr = (int[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    break;
                }
                case OP_LALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    long[] arr = (long[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    stack[sp++] = null;
                    break;
                }
                case OP_FALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    float[] arr = (float[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    break;
                }
                case OP_DALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    double[] arr = (double[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    stack[sp++] = null;
                    break;
                }
                case OP_AALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    Object[] arr = (Object[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    break;
                }
                case OP_BALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    Object arr = stack[--sp];
                    if (arr instanceof boolean[]) {
                        stack[sp++] = ((boolean[]) arr)[idx] ? 1 : 0;
                    } else {
                        stack[sp++] = (int) ((byte[]) arr)[idx];
                    }
                    break;
                }
                case OP_CALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    char[] arr = (char[]) stack[--sp];
                    stack[sp++] = (int) arr[idx];
                    break;
                }
                case OP_SALOAD: {
                    int idx = ((Integer) stack[--sp]);
                    short[] arr = (short[]) stack[--sp];
                    stack[sp++] = (int) arr[idx];
                    break;
                }
                case OP_IASTORE: {
                    int val = ((Number) stack[--sp]).intValue();
                    int idx = ((Integer) stack[--sp]);
                    int[] arr = (int[]) stack[--sp];
                    arr[idx] = val;
                    break;
                }
                case OP_LASTORE: {
                    sp -= 2;
                    long val = ((Number) stack[sp]).longValue();
                    int idx = ((Integer) stack[--sp]);
                    long[] arr = (long[]) stack[--sp];
                    arr[idx] = val;
                    break;
                }
                case OP_FASTORE: {
                    float val = ((Number) stack[--sp]).floatValue();
                    int idx = ((Integer) stack[--sp]);
                    float[] arr = (float[]) stack[--sp];
                    arr[idx] = val;
                    break;
                }
                case OP_DASTORE: {
                    sp -= 2;
                    double val = ((Number) stack[sp]).doubleValue();
                    int idx = ((Integer) stack[--sp]);
                    double[] arr = (double[]) stack[--sp];
                    arr[idx] = val;
                    break;
                }
                case OP_AASTORE: {
                    Object val = stack[--sp];
                    int idx = ((Integer) stack[--sp]);
                    Object[] arr = (Object[]) stack[--sp];
                    arr[idx] = val;
                    break;
                }
                case OP_BASTORE: {
                    int val = ((Number) stack[--sp]).intValue();
                    int idx = ((Integer) stack[--sp]);
                    Object arr = stack[--sp];
                    if (arr instanceof boolean[]) {
                        ((boolean[]) arr)[idx] = (val != 0);
                    } else {
                        ((byte[]) arr)[idx] = (byte) val;
                    }
                    break;
                }
                case OP_CASTORE: {
                    int val = ((Number) stack[--sp]).intValue();
                    int idx = ((Integer) stack[--sp]);
                    char[] arr = (char[]) stack[--sp];
                    arr[idx] = (char) val;
                    break;
                }
                case OP_SASTORE: {
                    int val = ((Number) stack[--sp]).intValue();
                    int idx = ((Integer) stack[--sp]);
                    short[] arr = (short[]) stack[--sp];
                    arr[idx] = (short) val;
                    break;
                }
                case OP_POP:
                    sp--;
                    break;
                case OP_POP2:
                    sp -= 2;
                    break;
                case OP_DUP:
                    stack[sp] = stack[sp-1];
                    sp++;
                    break;
                case OP_DUP_X1: {
                    Object val1 = stack[--sp];
                    Object val2 = stack[--sp];
                    stack[sp++] = val1;
                    stack[sp++] = val2;
                    stack[sp++] = val1;
                    break;
                }
                case OP_DUP_X2: {
                    Object val1 = stack[--sp];
                    Object val2 = stack[--sp];
                    Object val3 = stack[--sp];
                    stack[sp++] = val1;
                    stack[sp++] = val3;
                    stack[sp++] = val2;
                    stack[sp++] = val1;
                    break;
                }
                case OP_DUP2: {
                    Object val1 = stack[sp-1];
                    Object val2 = stack[sp-2];
                    stack[sp] = val2;
                    stack[sp+1] = val1;
                    sp += 2;
                    break;
                }
                case OP_DUP2_X1: {
                    Object val1 = stack[--sp];
                    Object val2 = stack[--sp];
                    Object val3 = stack[--sp];
                    stack[sp++] = val2;
                    stack[sp++] = val1;
                    stack[sp++] = val3;
                    stack[sp++] = val2;
                    stack[sp++] = val1;
                    break;
                }
                case OP_DUP2_X2: {
                    Object val1 = stack[--sp];
                    Object val2 = stack[--sp];
                    Object val3 = stack[--sp];
                    Object val4 = stack[--sp];
                    stack[sp++] = val2;
                    stack[sp++] = val1;
                    stack[sp++] = val4;
                    stack[sp++] = val3;
                    stack[sp++] = val2;
                    stack[sp++] = val1;
                    break;
                }
                case OP_SWAP: {
                    Object val1 = stack[--sp];
                    Object val2 = stack[--sp];
                    stack[sp++] = val1;
                    stack[sp++] = val2;
                    break;
                }
                case OP_IADD: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 + v2;
                    break;
                }
                case OP_LADD: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 + v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_FADD: {
                    float v2 = ((Float) stack[--sp]);
                    float v1 = ((Float) stack[--sp]);
                    stack[sp++] = v1 + v2;
                    break;
                }
                case OP_DADD: {
                    sp -= 2;
                    double v2 = ((Double) stack[sp]);
                    sp -= 2;
                    double v1 = ((Double) stack[sp]);
                    stack[sp++] = v1 + v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_ISUB: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 - v2;
                    break;
                }
                case OP_LSUB: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 - v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_FSUB: {
                    float v2 = ((Float) stack[--sp]);
                    float v1 = ((Float) stack[--sp]);
                    stack[sp++] = v1 - v2;
                    break;
                }
                case OP_DSUB: {
                    sp -= 2;
                    double v2 = ((Double) stack[sp]);
                    sp -= 2;
                    double v1 = ((Double) stack[sp]);
                    stack[sp++] = v1 - v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_IMUL: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 * v2;
                    break;
                }
                case OP_LMUL: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 * v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_FMUL: {
                    float v2 = ((Float) stack[--sp]);
                    float v1 = ((Float) stack[--sp]);
                    stack[sp++] = v1 * v2;
                    break;
                }
                case OP_DMUL: {
                    sp -= 2;
                    double v2 = ((Double) stack[sp]);
                    sp -= 2;
                    double v1 = ((Double) stack[sp]);
                    stack[sp++] = v1 * v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_IDIV: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 / v2;
                    break;
                }
                case OP_LDIV: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 / v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_FDIV: {
                    float v2 = ((Float) stack[--sp]);
                    float v1 = ((Float) stack[--sp]);
                    stack[sp++] = v1 / v2;
                    break;
                }
                case OP_DDIV: {
                    sp -= 2;
                    double v2 = ((Double) stack[sp]);
                    sp -= 2;
                    double v1 = ((Double) stack[sp]);
                    stack[sp++] = v1 / v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_IREM: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 % v2;
                    break;
                }
                case OP_LREM: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 % v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_FREM: {
                    float v2 = ((Float) stack[--sp]);
                    float v1 = ((Float) stack[--sp]);
                    stack[sp++] = v1 % v2;
                    break;
                }
                case OP_DREM: {
                    sp -= 2;
                    double v2 = ((Double) stack[sp]);
                    sp -= 2;
                    double v1 = ((Double) stack[sp]);
                    stack[sp++] = v1 % v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_INEG:
                    stack[sp-1] = -((Integer) stack[sp-1]);
                    break;
                case OP_LNEG:
                    stack[sp-2] = -((Long) stack[sp-2]);
                    break;
                case OP_FNEG:
                    stack[sp-1] = -((Float) stack[sp-1]);
                    break;
                case OP_DNEG:
                    stack[sp-2] = -((Double) stack[sp-2]);
                    break;
                case OP_ISHL: {
                    int s = ((Integer) stack[--sp]);
                    int v = ((Integer) stack[--sp]);
                    stack[sp++] = v << s;
                    break;
                }
                case OP_LSHL: {
                    int s = ((Integer) stack[--sp]);
                    sp -= 2;
                    long v = ((Long) stack[sp]);
                    stack[sp++] = v << s;
                    stack[sp++] = null;
                    break;
                }
                case OP_ISHR: {
                    int s = ((Integer) stack[--sp]);
                    int v = ((Integer) stack[--sp]);
                    stack[sp++] = v >> s;
                    break;
                }
                case OP_LSHR: {
                    int s = ((Integer) stack[--sp]);
                    sp -= 2;
                    long v = ((Long) stack[sp]);
                    stack[sp++] = v >> s;
                    stack[sp++] = null;
                    break;
                }
                case OP_IUSHR: {
                    int s = ((Integer) stack[--sp]);
                    int v = ((Integer) stack[--sp]);
                    stack[sp++] = v >>> s;
                    break;
                }
                case OP_LUSHR: {
                    int s = ((Integer) stack[--sp]);
                    sp -= 2;
                    long v = ((Long) stack[sp]);
                    stack[sp++] = v >>> s;
                    stack[sp++] = null;
                    break;
                }
                case OP_IAND: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 & v2;
                    break;
                }
                case OP_LAND: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 & v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_IOR: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 | v2;
                    break;
                }
                case OP_LOR: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 | v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_IXOR: {
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    stack[sp++] = v1 ^ v2;
                    break;
                }
                case OP_LXOR: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = v1 ^ v2;
                    stack[sp++] = null;
                    break;
                }
                case OP_IINC: {
                    int varIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    int inc = (short) (((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF));
                    locals[varIdx] = ((Number) locals[varIdx]).intValue() + inc;
                    break;
                }
                case OP_I2L:
                    stack[sp-1] = Long.valueOf(((Number) stack[sp-1]).longValue());
                    stack[sp++] = null;
                    break;
                case OP_I2F:
                    stack[sp-1] = Float.valueOf(((Number) stack[sp-1]).floatValue());
                    break;
                case OP_I2D:
                    stack[sp-1] = Double.valueOf(((Number) stack[sp-1]).doubleValue());
                    stack[sp++] = null;
                    break;
                case OP_L2I:
                    sp -= 2;
                    stack[sp++] = Integer.valueOf(((Number) stack[sp]).intValue());
                    break;
                case OP_L2F:
                    sp -= 2;
                    stack[sp++] = Float.valueOf(((Number) stack[sp]).floatValue());
                    break;
                case OP_L2D:
                    sp -= 2;
                    stack[sp++] = Double.valueOf(((Number) stack[sp]).doubleValue());
                    stack[sp++] = null;
                    break;
                case OP_F2I:
                    stack[sp-1] = Integer.valueOf(((Number) stack[sp-1]).intValue());
                    break;
                case OP_F2L:
                    stack[sp-1] = Long.valueOf(((Number) stack[sp-1]).longValue());
                    stack[sp++] = null;
                    break;
                case OP_F2D:
                    stack[sp-1] = Double.valueOf(((Number) stack[sp-1]).doubleValue());
                    stack[sp++] = null;
                    break;
                case OP_D2I:
                    sp -= 2;
                    stack[sp++] = Integer.valueOf(((Number) stack[sp]).intValue());
                    break;
                case OP_D2L:
                    sp -= 2;
                    stack[sp++] = Long.valueOf(((Number) stack[sp]).longValue());
                    stack[sp++] = null;
                    break;
                case OP_D2F:
                    sp -= 2;
                    stack[sp++] = Float.valueOf(((Number) stack[sp]).floatValue());
                    break;
                case OP_I2B:
                    stack[sp-1] = Byte.valueOf(((Number) stack[sp-1]).byteValue());
                    break;
                case OP_I2C:
                    stack[sp-1] = Character.valueOf((char) ((Number) stack[sp-1]).intValue());
                    break;
                case OP_I2S:
                    stack[sp-1] = Short.valueOf(((Number) stack[sp-1]).shortValue());
                    break;
                case OP_LCMP: {
                    sp -= 2;
                    long v2 = ((Long) stack[sp]);
                    sp -= 2;
                    long v1 = ((Long) stack[sp]);
                    stack[sp++] = (v1 < v2) ? -1 : ((v1 > v2) ? 1 : 0);
                    break;
                }
                case OP_FCMPL:
                case OP_FCMPG: {
                    float v2 = ((Float) stack[--sp]);
                    float v1 = ((Float) stack[--sp]);
                    if (Float.isNaN(v1) || Float.isNaN(v2)) {
                        stack[sp++] = (op == OP_FCMPG) ? 1 : -1;
                    } else {
                        stack[sp++] = (v1 < v2) ? -1 : ((v1 > v2) ? 1 : 0);
                    }
                    break;
                }
                case OP_DCMPL:
                case OP_DCMPG: {
                    sp -= 2;
                    double v2 = ((Double) stack[sp]);
                    sp -= 2;
                    double v1 = ((Double) stack[sp]);
                    if (Double.isNaN(v1) || Double.isNaN(v2)) {
                        stack[sp++] = (op == OP_DCMPG) ? 1 : -1;
                    } else {
                        stack[sp++] = (v1 < v2) ? -1 : ((v1 > v2) ? 1 : 0);
                    }
                    break;
                }
                case OP_IFEQ: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val == 0) pc = target;
                    break;
                }
                case OP_IFNE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val != 0) pc = target;
                    break;
                }
                case OP_IFLT: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val < 0) pc = target;
                    break;
                }
                case OP_IFGE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val >= 0) pc = target;
                    break;
                }
                case OP_IFGT: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val > 0) pc = target;
                    break;
                }
                case OP_IFLE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val <= 0) pc = target;
                    break;
                }
                case OP_IF_ICMPEQ: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    if (v1 == v2) pc = target;
                    break;
                }
                case OP_IF_ICMPNE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    if (v1 != v2) pc = target;
                    break;
                }
                case OP_IF_ICMPLT: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    if (v1 < v2) pc = target;
                    break;
                }
                case OP_IF_ICMPGE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    if (v1 >= v2) pc = target;
                    break;
                }
                case OP_IF_ICMPGT: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    if (v1 > v2) pc = target;
                    break;
                }
                case OP_IF_ICMPLE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    int v2 = ((Integer) stack[--sp]);
                    int v1 = ((Integer) stack[--sp]);
                    if (v1 <= v2) pc = target;
                    break;
                }
                case OP_IF_ACMPEQ: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    Object v2 = stack[--sp];
                    Object v1 = stack[--sp];
                    if (v1 == v2) pc = target;
                    break;
                }
                case OP_IF_ACMPNE: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    Object v2 = stack[--sp];
                    Object v1 = stack[--sp];
                    if (v1 != v2) pc = target;
                    break;
                }
                case OP_GOTO:
                    pc = readInt(bytecode, pc);
                    break;
                case OP_TABLESWITCH: {
                    int defaultTarget = readInt(bytecode, pc);
                    pc += 4;
                    int low = readInt(bytecode, pc);
                    pc += 4;
                    int high = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    if (val < low || val > high) {
                        pc = defaultTarget;
                    } else {
                        pc = readInt(bytecode, pc + (val - low) * 4);
                    }
                    break;
                }
                case OP_LOOKUPSWITCH: {
                    int defaultTarget = readInt(bytecode, pc);
                    pc += 4;
                    int npairs = readInt(bytecode, pc);
                    pc += 4;
                    int val = ((Integer) stack[--sp]);
                    boolean found = false;
                    for (int i = 0; i < npairs; i++) {
                        int key = readInt(bytecode, pc + i * 8);
                        if (key == val) {
                            pc = readInt(bytecode, pc + i * 8 + 4);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        pc = defaultTarget;
                    }
                    break;
                }
                case OP_IRETURN:
                case OP_ARETURN:
                case OP_FRETURN:
                    return stack[--sp];
                case OP_LRETURN:
                case OP_DRETURN:
                    sp -= 2;
                    return stack[sp];
                case OP_RETURN:
                    return null;
                case OP_GETSTATIC: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    FieldRef ref = (FieldRef) constPool[cpIdx];
                    java.lang.reflect.Field f = resolveField(ref);
                    Object val = f.get(null);
                    pushValue(stack, sp, val, f.getType());
                    sp += getSlotSize(f.getType());
                    break;
                }
                case OP_PUTSTATIC: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    FieldRef ref = (FieldRef) constPool[cpIdx];
                    java.lang.reflect.Field f = resolveField(ref);
                    Class<?> fType = f.getType();
                    int size = getSlotSize(fType);
                    sp -= size;
                    Object val = stack[sp];
                    f.set(null, coerce(val, fType));
                    break;
                }
                case OP_GETFIELD: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    FieldRef ref = (FieldRef) constPool[cpIdx];
                    java.lang.reflect.Field f = resolveField(ref);
                    Object obj = stack[--sp];
                    Object val = f.get(obj);
                    pushValue(stack, sp, val, f.getType());
                    sp += getSlotSize(f.getType());
                    break;
                }
                case OP_PUTFIELD: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    FieldRef ref = (FieldRef) constPool[cpIdx];
                    java.lang.reflect.Field f = resolveField(ref);
                    Class<?> fType = f.getType();
                    int size = getSlotSize(fType);
                    sp -= size;
                    Object val = stack[sp];
                    Object obj = stack[--sp];
                    f.set(obj, coerce(val, fType));
                    break;
                }
                case OP_INVOKEVIRTUAL:
                case OP_INVOKEINTERFACE:
                case OP_INVOKESTATIC:
                case OP_INVOKESPECIAL: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    MethodRef ref = (MethodRef) constPool[cpIdx];
                    Class<?>[] paramTypes = parseDesc(ref.desc, resolveLoader());
                    int[] spPtr = new int[] { sp };
                    Object[] mArgs = popArgs(stack, spPtr, paramTypes);
                    sp = spPtr[0];

                    Object receiver = null;
                    if (op != OP_INVOKESTATIC) {
                        receiver = stack[--sp];
                    }

                    Object result;
                    if (receiver instanceof NewPlaceholder) {
                        NewPlaceholder placeholder = (NewPlaceholder) receiver;
                        java.lang.reflect.Constructor<?> c = placeholder.clazz.getDeclaredConstructor(paramTypes);
                        c.setAccessible(true);
                        Object realObj = c.newInstance(mArgs);
                        for (int idx = 0; idx < sp; idx++) {
                            if (stack[idx] == placeholder) stack[idx] = realObj;
                        }
                        for (int idx = 0; idx < locals.length; idx++) {
                            if (locals[idx] == placeholder) locals[idx] = realObj;
                        }
                        result = null;
                    } else {
                        if (op == OP_INVOKESTATIC) {
                            java.lang.reflect.Method m = resolveMethod(ref, paramTypes);
                            result = m.invoke(null, mArgs);
                        } else if (op == OP_INVOKESPECIAL) {
                            result = invokeSpecialMethod(ref, receiver, mArgs, paramTypes);
                        } else {
                            java.lang.reflect.Method m = resolveMethod(ref, paramTypes);
                            result = m.invoke(receiver, mArgs);
                        }
                    }

                    Class<?> retType = parseReturnType(ref.desc, resolveLoader());
                    if (retType != void.class) {
                        pushValue(stack, sp, result, retType);
                        sp += getSlotSize(retType);
                    }
                    break;
                }
                case OP_NEW: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    String className = (String) constPool[cpIdx];
                    Class<?> clazz = loadClass(className);
                    stack[sp++] = new NewPlaceholder(clazz, nextPlaceholderId++);
                    break;
                }
                case OP_NEWARRAY: {
                    int count = ((Integer) stack[--sp]);
                    int type = bytecode[pc++] & 0xFF;
                    Object arr;
                    switch (type) {
                        case 4: arr = new boolean[count]; break;
                        case 5: arr = new char[count]; break;
                        case 6: arr = new float[count]; break;
                        case 7: arr = new double[count]; break;
                        case 8: arr = new byte[count]; break;
                        case 9: arr = new short[count]; break;
                        case 10: arr = new int[count]; break;
                        case 11: arr = new long[count]; break;
                        default: throw new VerifyError("Invalid NEWARRAY type: " + type);
                    }
                    stack[sp++] = arr;
                    break;
                }
                case OP_ANEWARRAY: {
                    int count = ((Integer) stack[--sp]);
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    String typeName = (String) constPool[cpIdx];
                    Class<?> elemClass = loadClass(typeName);
                    stack[sp++] = java.lang.reflect.Array.newInstance(elemClass, count);
                    break;
                }
                case OP_ARRAYLENGTH: {
                    Object arr = stack[--sp];
                    stack[sp++] = java.lang.reflect.Array.getLength(arr);
                    break;
                }
                case OP_ATHROW: {
                    Throwable t = (Throwable) stack[--sp];
                    throw t;
                }
                case OP_CHECKCAST: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    String typeName = (String) constPool[cpIdx];
                    Class<?> clazz = loadClass(typeName);
                    Object obj = stack[sp-1];
                    if (obj != null && !clazz.isInstance(obj)) {
                        throw new ClassCastException("Cannot cast " + obj.getClass().getName() + " to " + clazz.getName());
                    }
                    break;
                }
                case OP_INSTANCEOF: {
                    int cpIdx = ((bytecode[pc++] & 0xFF) << 8) | (bytecode[pc++] & 0xFF);
                    String typeName = (String) constPool[cpIdx];
                    Class<?> clazz = loadClass(typeName);
                    Object obj = stack[--sp];
                    stack[sp++] = (obj != null && clazz.isInstance(obj)) ? 1 : 0;
                    break;
                }
                case OP_MONITORENTER: {
                    throw new UnsupportedOperationException("Synchronized blocks are not supported in virtualized methods");
                }
                case OP_MONITOREXIT: {
                    throw new UnsupportedOperationException("Synchronized blocks are not supported in virtualized methods");
                }
                case OP_IFNULL: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    Object val = stack[--sp];
                    if (val == null) pc = target;
                    break;
                }
                case OP_IFNONNULL: {
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    Object val = stack[--sp];
                    if (val != null) pc = target;
                    break;
                }
                case OP_TRAP:
                default:
                    throw new VerifyError("VM execution trap/invalid instruction");
            }
        }

        throw new VerifyError("VM run off end of bytecode without return");
    }
}
