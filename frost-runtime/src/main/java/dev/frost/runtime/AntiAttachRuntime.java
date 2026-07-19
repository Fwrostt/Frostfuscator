package dev.frost.runtime;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

public final class AntiAttachRuntime {
    private AntiAttachRuntime() {
    }

    public static void verify(boolean requireDisableAttach,
                              boolean requireDynamicAgentDisabled,
                              boolean rejectAgents,
                              boolean rejectAttachListener,
                              String failureAction) {
        String args = inputArguments();
        if (requireDisableAttach && !args.contains("-xx:+disableattachmechanism")) {
            fail("JVM attach mechanism is not disabled", failureAction);
        }
        if (requireDynamicAgentDisabled && !args.contains("-xx:-enabledynamicagentloading")) {
            fail("Dynamic Java agent loading is not disabled", failureAction);
        }
        if (rejectAgents && containsAny(args, "-javaagent", "-agentlib", "-agentpath", "jdwp")) {
            fail("Java agent or debugger launch argument detected", failureAction);
        }
        if (rejectAttachListener && hasAttachListenerThread()) {
            fail("Attach listener thread detected", failureAction);
        }
    }

    private static String inputArguments() {
        try {
            List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            return String.join(" ", arguments).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean hasAttachListenerThread() {
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                String name = thread.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).contains("attach listener")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void fail(String message, String action) {
        String normalized = action == null ? "throw" : action.toLowerCase(Locale.ROOT);
        if ("warn".equals(normalized)) {
            System.err.println(message);
            return;
        }
        if ("exit".equals(normalized)) {
            System.exit(1);
        }
        if ("halt".equals(normalized)) {
            Runtime.getRuntime().halt(1);
        }
        throw new IllegalStateException(message);
    }
}
