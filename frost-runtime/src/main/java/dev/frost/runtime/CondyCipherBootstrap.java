package dev.frost.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Base64;

/** Runtime bootstrap embedded and relocated by the Condy cipher-stream transformer. */
public final class CondyCipherBootstrap {
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private CondyCipherBootstrap() {
    }

    public static Object key(MethodHandles.Lookup lookup, String name, Class<?> type,
                             long encodedKey, long nonce) {
        try {
            if (type != long.class) {
                throw failure(null);
            }
            long mask = keyMask(lookup.lookupClass().getName(), name, nonce);
            return encodedKey ^ mask;
        } catch (BootstrapMethodError error) {
            throw error;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    public static Object value(MethodHandles.Lookup lookup, String name, Class<?> type,
                               String payload0, String payload1, String payload2, String payload3,
                               long nonce, long encodedTag,
                               long key, int encodedKind) {
        byte[] plain = null;
        char[] characters = null;
        try {
            int kind = encodedKind ^ (int) nonce;
            plain = Base64.getUrlDecoder().decode(payload0 + payload1 + payload2 + payload3);
            applyStream(plain, streamSeed(lookup.lookupClass().getName(), name,
                    type.getName(), key, nonce, kind));
            long expectedTag = encodedTag ^ mix64(key + nonce + 0x6a09e667f3bcc909L);
            if (authenticationTag(plain, key, nonce, lookup.lookupClass().getName(), kind) != expectedTag) {
                throw failure(null);
            }
            characters = decodeModifiedUtf8(plain);
            String decoded = new String(characters);
            return resolve(lookup, type, decoded, kind);
        } catch (BootstrapMethodError error) {
            throw error;
        } catch (Throwable failure) {
            throw failure(failure);
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (characters != null) Arrays.fill(characters, '\0');
        }
    }

    private static Object resolve(MethodHandles.Lookup lookup, Class<?> type,
                                  String decoded, int kind) throws Throwable {
        return switch (kind) {
            case 1 -> decoded;
            case 2 -> Integer.valueOf(decoded);
            case 3 -> Long.valueOf(decoded);
            case 4 -> Float.intBitsToFloat((int) Long.parseUnsignedLong(decoded, 16));
            case 5 -> Double.longBitsToDouble(Long.parseUnsignedLong(decoded, 16));
            case 6 -> classForDescriptor(decoded, lookup.lookupClass().getClassLoader());
            case 7 -> MethodType.fromMethodDescriptorString(decoded, lookup.lookupClass().getClassLoader());
            case 8 -> methodHandle(lookup, decoded);
            case 9 -> varHandle(lookup, decoded);
            default -> throw failure(null);
        };
    }

    private static MethodHandle methodHandle(MethodHandles.Lookup lookup, String metadata) throws Exception {
        String[] parts = unpack(metadata, 5);
        int tag = Integer.parseInt(parts[0]);
        ClassLoader loader = lookup.lookupClass().getClassLoader();
        Class<?> owner = classForInternalName(parts[1], loader);
        String name = parts[2];
        String descriptor = parts[3];
        return switch (tag) {
            case 1 -> lookup.findGetter(owner, name, fieldType(descriptor, loader));
            case 2 -> lookup.findStaticGetter(owner, name, fieldType(descriptor, loader));
            case 3 -> lookup.findSetter(owner, name, fieldType(descriptor, loader));
            case 4 -> lookup.findStaticSetter(owner, name, fieldType(descriptor, loader));
            case 5, 9 -> lookup.findVirtual(owner, name,
                    MethodType.fromMethodDescriptorString(descriptor, loader));
            case 6 -> lookup.findStatic(owner, name,
                    MethodType.fromMethodDescriptorString(descriptor, loader));
            case 7 -> lookup.findSpecial(owner, name,
                    MethodType.fromMethodDescriptorString(descriptor, loader), lookup.lookupClass());
            case 8 -> lookup.findConstructor(owner,
                    MethodType.fromMethodDescriptorString(descriptor, loader));
            default -> throw failure(null);
        };
    }

    private static VarHandle varHandle(MethodHandles.Lookup lookup, String metadata) throws Exception {
        String[] parts = unpack(metadata, 4);
        ClassLoader loader = lookup.lookupClass().getClassLoader();
        Class<?> owner = classForInternalName(parts[0], loader);
        Class<?> fieldType = fieldType(parts[2], loader);
        return "1".equals(parts[3])
                ? lookup.findStaticVarHandle(owner, parts[1], fieldType)
                : lookup.findVarHandle(owner, parts[1], fieldType);
    }

    private static String[] unpack(String metadata, int count) {
        String[] values = new String[count];
        int cursor = 0;
        for (int index = 0; index < count; index++) {
            int separator = metadata.indexOf(':', cursor);
            if (separator < cursor) throw failure(null);
            int length;
            try {
                length = Integer.parseInt(metadata.substring(cursor, separator));
            } catch (NumberFormatException failure) {
                throw failure(failure);
            }
            int start = separator + 1;
            int end = start + length;
            if (length < 0 || end < start || end > metadata.length()) {
                throw failure(null);
            }
            values[index] = metadata.substring(start, end);
            cursor = end;
        }
        if (cursor != metadata.length()) throw failure(null);
        return values;
    }

    private static char[] decodeModifiedUtf8(byte[] data) {
        int characters = 0;
        for (int cursor = 0; cursor < data.length; characters++) {
            int first = data[cursor] & 0xff;
            if (first > 0 && first <= 0x7f) {
                cursor++;
            } else if ((first & 0xe0) == 0xc0) {
                requireContinuation(data, cursor, 1);
                int value = ((first & 0x1f) << 6) | (data[cursor + 1] & 0x3f);
                if (value != 0 && value < 0x80) throw failure(null);
                cursor += 2;
            } else if ((first & 0xf0) == 0xe0) {
                requireContinuation(data, cursor, 2);
                int value = ((first & 0x0f) << 12) | ((data[cursor + 1] & 0x3f) << 6)
                        | (data[cursor + 2] & 0x3f);
                if (value < 0x800) throw failure(null);
                cursor += 3;
            } else {
                throw failure(null);
            }
        }

        char[] result = new char[characters];
        int cursor = 0;
        for (int index = 0; index < result.length; index++) {
            int first = data[cursor] & 0xff;
            if (first > 0 && first <= 0x7f) {
                result[index] = (char) first;
                cursor++;
            } else if ((first & 0xe0) == 0xc0) {
                result[index] = (char) (((first & 0x1f) << 6) | (data[cursor + 1] & 0x3f));
                cursor += 2;
            } else {
                result[index] = (char) (((first & 0x0f) << 12) | ((data[cursor + 1] & 0x3f) << 6)
                        | (data[cursor + 2] & 0x3f));
                cursor += 3;
            }
        }
        return result;
    }

    private static void requireContinuation(byte[] data, int cursor, int count) {
        if (cursor + count >= data.length) throw failure(null);
        for (int offset = 1; offset <= count; offset++) {
            if ((data[cursor + offset] & 0xc0) != 0x80) {
                throw failure(null);
            }
        }
    }

    private static Class<?> fieldType(String descriptor, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + descriptor, loader).returnType();
    }

    private static Class<?> classForInternalName(String internalName, ClassLoader loader)
            throws ClassNotFoundException {
        if (internalName.startsWith("[")) {
            return Class.forName(internalName.replace('/', '.'), false, loader);
        }
        return Class.forName(internalName.replace('/', '.'), false, loader);
    }

    private static Class<?> classForDescriptor(String descriptor, ClassLoader loader) {
        if (descriptor.length() == 1) {
            return switch (descriptor.charAt(0)) {
                case 'V' -> void.class;
                case 'Z' -> boolean.class;
                case 'B' -> byte.class;
                case 'C' -> char.class;
                case 'S' -> short.class;
                case 'I' -> int.class;
                case 'F' -> float.class;
                case 'J' -> long.class;
                case 'D' -> double.class;
                default -> throw failure(null);
            };
        }
        return fieldType(descriptor, loader);
    }

    private static void applyStream(byte[] data, long seed) {
        long state = seed;
        long block = 0L;
        for (int index = 0; index < data.length; index++) {
            if ((index & 7) == 0) {
                state += GOLDEN_GAMMA;
                block = mix64(state);
            }
            data[index] ^= (byte) (block >>> ((index & 7) << 3));
        }
    }

    private static long streamSeed(String owner, String name, String type,
                                   long key, long nonce, int kind) {
        long seed = key ^ Long.rotateLeft(nonce, 17) ^ ((long) kind * GOLDEN_GAMMA);
        seed ^= hash64(owner);
        seed = mix64(seed ^ hash64(name));
        return mix64(seed ^ hash64(type));
    }

    private static long keyMask(String owner, String name, long nonce) {
        return mix64(hash64(owner) ^ Long.rotateLeft(hash64(name), 23)
                ^ nonce ^ 0x243f6a8885a308d3L);
    }

    private static long authenticationTag(byte[] data, long key, long nonce,
                                          String owner, int kind) {
        long hash = mix64(key ^ nonce ^ hash64(owner) ^ ((long) kind * GOLDEN_GAMMA));
        for (byte value : data) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
            hash = Long.rotateLeft(hash, 11);
        }
        return mix64(hash ^ data.length);
    }

    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static BootstrapMethodError failure(Throwable cause) {
        BootstrapMethodError error = new BootstrapMethodError();
        if (cause != null) error.initCause(cause);
        return error;
    }
}
