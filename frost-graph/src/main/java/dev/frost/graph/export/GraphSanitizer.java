package dev.frost.graph.export;

/** Escapes all untrusted labels before they enter renderer syntax or HTML. */
public final class GraphSanitizer {
    private GraphSanitizer() {
    }

    public static String mermaidLabel(String value) {
        return html(value).replace("`", "&#96;");
    }

    public static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\r\n", "<br/>")
                .replace("\n", "<br/>")
                .replace("\r", "<br/>");
    }

    public static String dot(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}
