package dev.frost.obfuscator.gui.viewer;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaSyntax {
    private static final String[] KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "exports", "extends",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "module", "native", "new", "non-sealed", "open", "opens",
            "package", "permits", "private", "protected", "provides", "public", "record", "requires",
            "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "to", "transient", "transitive", "try", "uses", "var", "void",
            "volatile", "while", "with", "yield"
    };

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")"
                    + "|(?<CHAR>'(?:\\\\.|[^'\\\\])')"
                    + "|(?<ANNOTATION>@[\\w$.]+)"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<METHOD>\\b[a-zA-Z_$][\\w$]*(?=\\s*\\())"
                    + "|(?<TYPE>\\b[A-Z][\\w$]*\\b)"
                    + "|(?<NUMBER>\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)[fFdDlL]?\\b)"
                    + "|(?<PAREN>[()])"
                    + "|(?<BRACE>[{}])"
                    + "|(?<BRACKET>[\\[\\]])"
                    + "|(?<SEMICOLON>;)"
    );

    private JavaSyntax() {}

    public static StyleSpans<Collection<String>> highlight(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int last = 0;
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        while (matcher.find()) {
            spans.add(Collections.emptyList(), matcher.start() - last);
            String style = matcher.group("KEYWORD") != null ? "syntax-keyword"
                    : matcher.group("ANNOTATION") != null ? "syntax-annotation"
                    : matcher.group("STRING") != null || matcher.group("CHAR") != null ? "syntax-string"
                    : matcher.group("NUMBER") != null ? "syntax-number"
                    : matcher.group("COMMENT") != null ? "syntax-comment"
                    : matcher.group("METHOD") != null ? "syntax-method"
                    : matcher.group("TYPE") != null ? "syntax-type"
                    : "syntax-punctuation";
            spans.add(Collections.singleton(style), matcher.end() - matcher.start());
            last = matcher.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }
}
