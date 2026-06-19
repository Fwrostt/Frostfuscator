package dev.frost.obfuscator.transformer.reporting;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class StatisticsReportTransformer extends Transformer {

    @Override
    public String getName() {
        return "statistics-report";
    }

    @Override
    public String getCategory() {
        return "Reporting";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        try {
            Path output = Path.of(context.config().getOption("output", "frost-report.json"));
            String format = context.config().getOption("format", "json");
            context.stats().set("classes", context.pool().size());
            context.stats().set("methods", methodCount(context));
            context.stats().set("resources", context.resources().size());
            context.stats().set("classMappings", context.mappings().getClassMappings().size());
            context.stats().set("fieldMappings", context.mappings().getFieldMappings().size());
            context.stats().set("methodMappings", context.mappings().getMethodMappings().size());
            context.stats().set("totalMappings", context.mappings().totalMappings());

            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }

            String report = "html".equalsIgnoreCase(format) ? html(context.stats().counters()) : json(context.stats().counters());
            Files.writeString(output, report);
            log("Wrote statistics report to {}", output);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write statistics report", e);
        }
    }

    private long methodCount(Context context) {
        long count = 0;
        for (ClassNode classNode : context.pool().getClasses()) {
            count += classNode.methods.size();
        }
        return count;
    }

    private String json(Map<String, Long> counters) {
        StringBuilder builder = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            builder.append("  \"").append(entry.getKey()).append("\": ").append(entry.getValue());
            builder.append(++i == counters.size() ? "\n" : ",\n");
        }
        return builder.append("}\n").toString();
    }

    private String html(Map<String, Long> counters) {
        StringBuilder builder = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Frostfuscator Report</title></head><body>");
        builder.append("<h1>Frostfuscator Report</h1><table><thead><tr><th>Metric</th><th>Value</th></tr></thead><tbody>");
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            builder.append("<tr><td>").append(entry.getKey()).append("</td><td>").append(entry.getValue()).append("</td></tr>");
        }
        builder.append("</tbody></table></body></html>");
        return builder.toString();
    }
}
