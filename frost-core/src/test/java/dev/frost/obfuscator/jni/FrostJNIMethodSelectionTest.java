package dev.frost.obfuscator.jni;

import dev.frost.obfuscator.jni.core.model.ClassModel;
import dev.frost.obfuscator.jni.core.model.MethodModel;
import dev.frost.obfuscator.jni.core.selection.NativeSelectionConfig;
import dev.frost.obfuscator.jni.core.selection.NativeSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrostJNIMethodSelectionTest {

    @Test
    void exactMethodTargetsSelectOnlyTheChosenOverload() {
        MethodModel integerOverload = method("run", "(I)V");
        MethodModel stringOverload = method("run", "(Ljava/lang/String;)V");
        ClassModel owner = new ClassModel("example/Service", "java/lang/Object", 0,
                List.of(integerOverload, stringOverload));
        NativeSelector selector = new NativeSelector(new NativeSelectionConfig(
                Set.of(), Set.of(), Set.of("example/Service#run(I)V"), Set.of()));

        assertTrue(selector.includeMethod(owner, integerOverload));
        assertFalse(selector.includeMethod(owner, stringOverload));
    }

    @Test
    void exactExclusionsSupportDottedOwnersAndLegacyBroadKeys() {
        assertTrue(FrostJNIProtectionService.matchesMethodTarget(
                List.of("example.Service#run(I)V"), "example/Service", "run", "(I)V"));
        assertFalse(FrostJNIProtectionService.matchesMethodTarget(
                List.of("example.Service#run(I)V"), "example/Service", "run", "(J)V"));
        assertTrue(FrostJNIProtectionService.matchesMethodTarget(
                List.of("example.Service#run"), "example/Service", "run", "(J)V"));
    }

    private static MethodModel method(String name, String descriptor) {
        return new MethodModel("example/Service", name, descriptor, 0, List.of(), List.of());
    }
}
