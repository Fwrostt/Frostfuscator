package dev.frost.obfuscator.gui.viewer;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BytecodeSyntax {
    private static final String[] KEYWORDS = {
            "class", "interface", "enum", "public", "private", "protected", "static",
            "final", "super", "synchronized", "volatile", "bridge", "varargs", "transient",
            "native", "strictfp", "synthetic", "module", "requires", "exports", "opens",
            "uses", "provides", "mandated", "throws"
    };

    private static final String[] DIRECTIVES = {
            "LINENUMBER", "LOCALVARIABLE", "MAXSTACK", "MAXLOCALS", "FRAME", "TRYCATCHBLOCK"
    };

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b|<(?:init|clinit)>)"
                    + "|(?<DIRECTIVE>\\b(?:" + String.join("|", DIRECTIVES) + ")\\b)"
                    + "|(?<LABEL>\\bL\\d+\\b)"
                    + "|(?<INSTRUCTION>\\b[A-Z][A-Z0-9_]+\\b)"
                    + "|(?<TYPE>L[^;\\s]+;)"
                    + "|(?<NUMBER>\\b-?\\d+\\b|0x[0-9a-fA-F]+)"
                    + "|(?<PAREN>[()])"
                    + "|(?<BRACE>[{}])"
                    + "|(?<BRACKET>[\\[\\]])"
    );

    private BytecodeSyntax() {}

    public static StyleSpans<Collection<String>> highlight(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int last = 0;
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        while (matcher.find()) {
            spans.add(Collections.emptyList(), matcher.start() - last);
            String style = matcher.group("KEYWORD") != null ? "syntax-keyword"
                    : matcher.group("DIRECTIVE") != null ? "syntax-directive"
                    : matcher.group("LABEL") != null ? "syntax-label"
                    : matcher.group("INSTRUCTION") != null ? "syntax-instruction"
                    : matcher.group("TYPE") != null ? "syntax-type"
                    : matcher.group("STRING") != null ? "syntax-string"
                    : matcher.group("NUMBER") != null ? "syntax-number"
                    : matcher.group("COMMENT") != null ? "syntax-comment"
                    : "syntax-punctuation";
            spans.add(Collections.singleton(style), matcher.end() - matcher.start());
            last = matcher.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }
}
