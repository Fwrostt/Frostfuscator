package dev.frost.obfuscator.gui.viewer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Indexes JAR symbols and standard JDK classes to provide IDE-style autocompletion
 * for imports, class references, methods, and fields.
 */
public final class IdeCompletionService {

    public enum CompletionKind { CLASS, METHOD, FIELD, PACKAGE }

    public record CompletionCandidate(
            String insertText,
            String displayText,
            String detail,
            CompletionKind kind
    ) {}

    public record ClassSymbol(
            String internalName,
            String simpleName,
            String packageName,
            List<MemberSymbol> members
    ) {}

    public record MemberSymbol(
            String name,
            String descriptor,
            String displaySignature,
            boolean isStatic,
            boolean isMethod
    ) {}

    private final Map<String, ClassSymbol> indexedClasses = new HashMap<>();
    private static final List<Class<?>> BUILTIN_JDK_CLASSES = List.of(
            java.util.Base64.class,
            java.util.Arrays.class,
            java.util.List.class,
            java.util.ArrayList.class,
            java.util.Map.class,
            java.util.HashMap.class,
            java.util.Set.class,
            java.util.HashSet.class,
            java.util.Collections.class,
            java.util.Objects.class,
            java.util.Optional.class,
            java.util.concurrent.Callable.class,
            java.util.concurrent.CompletableFuture.class,
            java.util.stream.Collectors.class,
            java.io.File.class,
            java.io.InputStream.class,
            java.io.OutputStream.class,
            java.nio.file.Path.class,
            java.nio.file.Files.class,
            java.lang.System.class,
            java.lang.String.class,
            java.lang.Math.class,
            java.lang.StringBuilder.class,
            java.lang.Thread.class,
            java.lang.Runnable.class,
            java.lang.Exception.class,
            java.lang.RuntimeException.class,
            java.lang.IllegalArgumentException.class,
            java.lang.IllegalStateException.class
    );

    public IdeCompletionService() {
        indexJdkClasses();
    }

    public void indexJar(Map<String, byte[]> classPool) {
        if (classPool == null) return;
        for (Map.Entry<String, byte[]> entry : classPool.entrySet()) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                indexClassNode(node);
            } catch (Exception ignored) {}
        }
    }

    private void indexClassNode(ClassNode node) {
        String internalName = node.name;
        String fqcn = internalName.replace('/', '.');
        int lastDot = fqcn.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
        String packageName = lastDot >= 0 ? fqcn.substring(0, lastDot) : "";

        List<MemberSymbol> members = new ArrayList<>();
        for (FieldNode field : node.fields) {
            boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
            members.add(new MemberSymbol(field.name, field.desc, field.name + " : " + formatDesc(field.desc), isStatic, false));
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) continue;
            boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            String sig = formatMethodSig(method.name, method.desc);
            members.add(new MemberSymbol(method.name, method.desc, sig, isStatic, true));
        }

        ClassSymbol symbol = new ClassSymbol(internalName, simpleName, packageName, members);
        indexedClasses.put(fqcn, symbol);
        indexedClasses.put(simpleName, symbol);
    }

    private void indexJdkClasses() {
        for (Class<?> clazz : BUILTIN_JDK_CLASSES) {
            String fqcn = clazz.getName();
            String simpleName = clazz.getSimpleName();
            String packageName = clazz.getPackageName();

            List<MemberSymbol> members = new ArrayList<>();
            for (Field field : clazz.getFields()) {
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                members.add(new MemberSymbol(field.getName(), "", field.getName() + " : " + field.getType().getSimpleName(), isStatic, false));
            }
            for (Method method : clazz.getMethods()) {
                boolean isStatic = Modifier.isStatic(method.getModifiers());
                StringBuilder params = new StringBuilder("(");
                Class<?>[] pTypes = method.getParameterTypes();
                for (int i = 0; i < pTypes.length; i++) {
                    params.append(pTypes[i].getSimpleName());
                    if (i < pTypes.length - 1) params.append(", ");
                }
                params.append(") : ").append(method.getReturnType().getSimpleName());
                members.add(new MemberSymbol(method.getName(), "", method.getName() + params, isStatic, true));
            }

            ClassSymbol symbol = new ClassSymbol(fqcn.replace('.', '/'), simpleName, packageName, members);
            indexedClasses.put(fqcn, symbol);
            indexedClasses.put(simpleName, symbol);
        }
    }

    public List<CompletionCandidate> getImportSuggestions(String prefix) {
        String filter = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        if (filter.startsWith("import ")) {
            filter = filter.substring(7).trim();
        }
        if (filter.endsWith(";")) {
            filter = filter.substring(0, filter.length() - 1).trim();
        }

        List<CompletionCandidate> results = new ArrayList<>();
        Set<String> added = new HashSet<>();

        for (Map.Entry<String, ClassSymbol> entry : indexedClasses.entrySet()) {
            String fqcn = entry.getKey();
            if (!fqcn.contains(".")) continue; // Skip simple name mappings to avoid duplicates
            ClassSymbol sym = entry.getValue();

            if (filter.isEmpty() || fqcn.toLowerCase(Locale.ROOT).startsWith(filter) || sym.simpleName.toLowerCase(Locale.ROOT).startsWith(filter)) {
                if (added.add(fqcn)) {
                    results.add(new CompletionCandidate(
                            fqcn + ";",
                            sym.simpleName,
                            "import " + fqcn,
                            CompletionKind.CLASS
                    ));
                }
            }
        }

        results.sort(Comparator.comparing(CompletionCandidate::displayText));
        return results.size() > 30 ? results.subList(0, 30) : results;
    }

    public List<CompletionCandidate> getMemberSuggestions(String targetName, String memberPrefix) {
        String target = targetName == null ? "" : targetName.trim();
        String filter = memberPrefix == null ? "" : memberPrefix.trim().toLowerCase(Locale.ROOT);

        ClassSymbol symbol = indexedClasses.get(target);
        if (symbol == null) {
            // Search by simple name
            for (ClassSymbol sym : indexedClasses.values()) {
                if (sym.simpleName.equals(target) || sym.packageName.endsWith("." + target)) {
                    symbol = sym;
                    break;
                }
            }
        }

        if (symbol == null) return List.of();

        List<CompletionCandidate> results = new ArrayList<>();
        Set<String> added = new HashSet<>();

        for (MemberSymbol member : symbol.members) {
            if (filter.isEmpty() || member.name.toLowerCase(Locale.ROOT).startsWith(filter)) {
                if (added.add(member.displaySignature)) {
                    String insert = member.name + (member.isMethod ? "()" : "");
                    results.add(new CompletionCandidate(
                            insert,
                            member.name,
                            member.displaySignature,
                            member.isMethod ? CompletionKind.METHOD : CompletionKind.FIELD
                    ));
                }
            }
        }

        results.sort(Comparator.comparing(CompletionCandidate::displayText));
        return results.size() > 40 ? results.subList(0, 40) : results;
    }

    private static String formatDesc(String desc) {
        if (desc == null || desc.isEmpty()) return "Object";
        try {
            return Type.getType(desc).getClassName();
        } catch (Exception e) {
            return desc;
        }
    }

    private static String formatMethodSig(String name, String desc) {
        if (desc == null || !desc.startsWith("(")) return name + "()";
        try {
            Type[] pTypes = Type.getArgumentTypes(desc);
            Type rType = Type.getReturnType(desc);
            StringBuilder sb = new StringBuilder(name).append("(");
            for (int i = 0; i < pTypes.length; i++) {
                sb.append(pTypes[i].getClassName());
                if (i < pTypes.length - 1) sb.append(", ");
            }
            sb.append(") : ").append(rType.getClassName());
            return sb.toString();
        } catch (Exception e) {
            return name + desc;
        }
    }
}
