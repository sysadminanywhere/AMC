package dev.amc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Lexical tokens for the Java method-body experiment. */
public final class JavaTokens {
    private static final Pattern TOKEN = Pattern.compile(
            "'[a-z]'|\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*|[0-9]+|==|!=|<=|>=|\\+=|&&|\\|\\||\\S");
    private static final Pattern SOURCE_TOKEN = Pattern.compile("[ \\t]+|(?s:.)");

    private JavaTokens() {}

    public static List<String> tokenize(String text) {
        return TOKEN.matcher(text).results().map(match -> match.group()).toList();
    }

    public static List<String> sourceTokens(String source) {
        return SOURCE_TOKEN.matcher(source).results().map(match -> match.group()).toList();
    }

    static List<String> promptFeatures(String prompt) {
        var tokens = tokenize(prompt.toLowerCase(Locale.ROOT));
        var features = new ArrayList<String>();
        features.add("bias");
        for (int i = 0; i < tokens.size(); i++) {
            features.add("word:" + tokens.get(i));
            features.add("position:" + i + ":" + tokens.get(i));
        }
        return features;
    }
}
