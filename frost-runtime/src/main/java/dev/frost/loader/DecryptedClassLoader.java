package dev.frost.loader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DecryptedClassLoader extends ClassLoader {
    private static final int MAGIC = 0x4652434c;
    private static final int VERSION = 2;
    private static final int FLAG_COMPRESSED = 1;

    private static final String ALGORITHM = "ALGORITHM_PLACEHOLDER";
    private static final String RESOURCE_PATH = "RESOURCE_PATH_PLACEHOLDER";
    private static final String AES_KEY_BASE64 = "AES_KEY_PLACEHOLDER";
    private static final String FAIL_ON_ERROR = "FAIL_ON_ERROR_PLACEHOLDER";

    private final Map<String, EncryptedClass> registry = new HashMap<>();
    private boolean initialized;

    public DecryptedClassLoader(ClassLoader parent) {
        super(parent);
    }

    public static void bootstrap(Class<?> hostClass) {
        try {
            ClassLoader hostLoader = hostClass.getClassLoader();
            Map<String, EncryptedClass> classes = readRegistry(hostClass);
            for (Map.Entry<String, EncryptedClass> entry : classes.entrySet()) {
                if (findLoaded(hostLoader, entry.getKey()) != null) {
                    continue;
                }
                byte[] bytes = decrypt(entry.getValue());
                defineInto(hostClass, hostLoader, entry.getKey(), bytes);
            }
        } catch (Throwable throwable) {
            if (Boolean.parseBoolean(FAIL_ON_ERROR)) {
                throw new RuntimeException("Failed to bootstrap encrypted classes", throwable);
            }
        }
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null && isEncryptedName(name)) {
            try {
                loaded = findClass(name);
            } catch (ClassNotFoundException ignored) {
                loaded = null;
            }
        }
        if (loaded == null) {
            loaded = super.loadClass(name, false);
        }
        if (resolve) {
            resolveClass(loaded);
        }
        return loaded;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        initRegistry();
        EncryptedClass encryptedClass = registry.remove(name);
        if (encryptedClass == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            byte[] bytes = decrypt(encryptedClass);
            return defineClass(name, bytes, 0, bytes.length);
        } catch (Exception exception) {
            throw new ClassNotFoundException("Failed to decrypt class: " + name, exception);
        }
    }

    private boolean isEncryptedName(String name) {
        initRegistry();
        return registry.containsKey(name);
    }

    private synchronized void initRegistry() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            registry.putAll(readRegistry(DecryptedClassLoader.class));
        } catch (Exception exception) {
            if (Boolean.parseBoolean(FAIL_ON_ERROR)) {
                throw new RuntimeException("Failed to initialize encrypted class registry", exception);
            }
        }
    }

    private static Map<String, EncryptedClass> readRegistry(Class<?> anchor) throws Exception {
        try (InputStream inputStream = anchor.getResourceAsStream("/" + RESOURCE_PATH)) {
            if (inputStream == null) {
                return Map.of();
            }
            DataInputStream input = new DataInputStream(inputStream);
            int magic = input.readInt();
            int version = input.readUnsignedByte();
            if (magic != MAGIC || version != VERSION) {
                throw new IllegalStateException("Unsupported encrypted class database");
            }
            int count = input.readInt();
            Map<String, EncryptedClass> storedClasses = new LinkedHashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                String storedName = input.readUTF();
                int flags = input.readUnsignedByte();
                int originalLength = input.readInt();
                int ivLength = input.readUnsignedByte();
                byte[] iv = new byte[ivLength];
                input.readFully(iv);
                int encryptedLength = input.readInt();
                byte[] encryptedBytes = new byte[encryptedLength];
                input.readFully(encryptedBytes);
                storedClasses.put(storedName,
                        new EncryptedClass(storedName, flags, originalLength, iv, encryptedBytes));
            }
            Map<String, EncryptedClass> classes = new LinkedHashMap<>();
            selectRegistryEntries(storedClasses.keySet(), Runtime.version().feature())
                    .forEach((binaryName, storedName) -> classes.put(binaryName, storedClasses.get(storedName)));
            return classes;
        }
    }

    private static byte[] decrypt(EncryptedClass encryptedClass) throws Exception {
        String encryptionName = encryptedClass.encryptionName;
        SecretKeySpec keySpec = new SecretKeySpec(deriveKey(encryptionName), "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        AlgorithmParameterSpec parameterSpec;
        if (ALGORITHM.contains("GCM")) {
            GCMParameterSpec gcm = new GCMParameterSpec(128, encryptedClass.iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcm);
            cipher.updateAAD(encryptionName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            parameterSpec = new IvParameterSpec(encryptedClass.iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);
        }
        byte[] bytes = cipher.doFinal(encryptedClass.encryptedBytes);
        if ((encryptedClass.flags & FLAG_COMPRESSED) != 0) {
            bytes = inflate(bytes, encryptedClass.originalLength);
        }
        return bytes;
    }

    static Map<String, String> selectRegistryEntries(Collection<String> storedNames, int runtimeFeature) {
        Map<String, String> selected = new LinkedHashMap<>();
        Map<String, Integer> selectedReleases = new HashMap<>();
        for (String storedName : storedNames) {
            int release = registryRelease(storedName);
            if (release < 0 || release > runtimeFeature) continue;
            String binaryName = registryBinaryName(storedName);
            if (binaryName.isEmpty()) continue;
            int selectedRelease = selectedReleases.getOrDefault(binaryName, -1);
            if (release >= selectedRelease) {
                selected.put(binaryName, storedName);
                selectedReleases.put(binaryName, release);
            }
        }
        return Map.copyOf(selected);
    }

    private static int registryRelease(String storedName) {
        String prefix = "META-INF/versions/";
        if (!storedName.startsWith(prefix)) return 0;
        int separator = storedName.indexOf('/', prefix.length());
        if (separator < 0) return -1;
        try {
            return Integer.parseInt(storedName.substring(prefix.length(), separator));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String registryBinaryName(String storedName) {
        String classPath = storedName;
        String prefix = "META-INF/versions/";
        if (storedName.startsWith(prefix)) {
            int separator = storedName.indexOf('/', prefix.length());
            if (separator < 0 || separator + 1 >= storedName.length()) return "";
            classPath = storedName.substring(separator + 1);
        }
        if (classPath.endsWith(".class")) classPath = classPath.substring(0, classPath.length() - 6);
        return classPath.replace('/', '.');
    }

    private static byte[] deriveKey(String name) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Base64.getDecoder().decode(AES_KEY_BASE64));
        digest.update((byte) 0);
        digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] full = digest.digest();
        byte[] key = new byte[16];
        System.arraycopy(full, 0, key, 0, key.length);
        return key;
    }

    private static byte[] inflate(byte[] input, int expectedLength) throws Exception {
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(input));
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32, expectedLength))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inflater.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Class<?> findLoaded(ClassLoader loader, String name) {
        try {
            Method method = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            method.setAccessible(true);
            return (Class<?>) method.invoke(loader, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void defineInto(Class<?> hostClass, ClassLoader hostLoader, String name, byte[] bytes) throws Throwable {
        String hostPackage = packageName(hostClass.getName());
        if (hostPackage.equals(packageName(name))) {
            MethodHandles.privateLookupIn(hostClass, MethodHandles.lookup()).defineClass(bytes);
            return;
        }

        throw new IllegalStateException("Encrypted plugin class is outside the plugin main package: " + name);
    }

    private static String packageName(String binaryName) {
        int index = binaryName.lastIndexOf('.');
        return index < 0 ? "" : binaryName.substring(0, index);
    }

    private static final class EncryptedClass {
        final String encryptionName;
        final int flags;
        final int originalLength;
        final byte[] iv;
        final byte[] encryptedBytes;

        EncryptedClass(String encryptionName, int flags, int originalLength, byte[] iv, byte[] encryptedBytes) {
            this.encryptionName = encryptionName;
            this.flags = flags;
            this.originalLength = originalLength;
            this.iv = iv;
            this.encryptedBytes = encryptedBytes;
        }
    }
}
