package dev.frost.runtime;

import java.lang.management.ManagementFactory;
import java.util.List;

public final class AntiAgentRuntime {

    private AntiAgentRuntime() {
    }

    public static void checkInstrumentationAndAgents() {
        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : inputArgs) {
            String lower = arg.toLowerCase();
            if (lower.startsWith("-javaagent:") || lower.startsWith("-agentlib:") || lower.startsWith("-agentpath:")
                    || lower.contains("bytebuddy") || lower.contains("aspectj") || lower.contains("jdwp")) {
                throw new IllegalStateException("Unauthorized agent or instrumentation detected: " + arg);
            }
        }

        // Verify System properties for ByteBuddy / agent injection markers
        String agentProp = System.getProperty("jdk.attach.allowAttachSelf");
        if ("true".equalsIgnoreCase(agentProp)) {
            System.setProperty("jdk.attach.allowAttachSelf", "false");
        }
    }
}
