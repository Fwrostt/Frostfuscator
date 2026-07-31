package dev.frost.obfuscator.gui.dialog;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveTargetPickerTest {

    @Test
    void formatsPackageAndClassRulesWithoutOvermatchingPrefixes() {
        String packageRule = ArchiveTargetPicker.Target.packageTarget("com.example").regexRule();
        assertTrue(Pattern.matches(packageRule, "com.example.Service"));
        assertTrue(Pattern.matches(packageRule, "com.example.internal.Helper"));
        assertFalse(Pattern.matches(packageRule, "com.examples.Service"));

        String classRule = ArchiveTargetPicker.Target.classTarget("com.example.Service").regexRule();
        assertTrue(Pattern.matches(classRule, "com.example.Service"));
        assertFalse(Pattern.matches(classRule, "com.example.ServiceFactory"));
    }

    @Test
    void defaultPackageRuleOnlyMatchesDefaultPackageClasses() {
        String rule = ArchiveTargetPicker.Target.packageTarget("").regexRule();

        assertTrue(Pattern.matches(rule, "Main"));
        assertFalse(Pattern.matches(rule, "app.Main"));
    }

    @Test
    void formatsExactJniMethodOverloads() {
        ArchiveTargetPicker.Target method = ArchiveTargetPicker.Target.methodTarget(
                "com.example.Service", "run", "(Ljava/lang/String;)Z");
        assertEquals("com.example.Service#run(Ljava/lang/String;)Z", method.jniValue());
    }
}
