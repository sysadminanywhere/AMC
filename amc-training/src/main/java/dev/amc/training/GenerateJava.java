package dev.amc.training;

import dev.amc.core.JavaLanguageModel;
import dev.amc.core.JavaTokens;

import java.io.IOException;
import java.nio.file.Path;

public final class GenerateJava {
    public static void main(String[] args) throws IOException {
        var invocation = JavaProblems.Invocation.parse(args);
        args = invocation.arguments();
        if (args.length != 2 || invocation.domains().size() != 1) {
            throw new IllegalArgumentException(
                    "Usage: GenerateJava [--arrays | --strings | --collections] weights-file \"description\"");
        }
        var domain = invocation.domains().getFirst();
        var model = JavaLanguageModel.load(Path.of(args[0]));
        if (model.sourceMode()) throw new IllegalArgumentException("Use GenerateProgram for a source-mode checkpoint");
        var generated = model.generate(domain.prompt(args[1]), domain.maxTokens());
        if (!generated.complete()) throw new IllegalStateException("Generation reached its token limit");
        if (domain == JavaProblems.Domain.COLLECTIONS) {
            System.out.println("import java.util.HashSet;");
            System.out.println("import java.util.Set;");
            System.out.println();
        }
        System.out.println(domain.signature() + " {");
        printBody(generated.body());
        System.out.println("}");
    }

    private static void printBody(String body) {
        var line = new StringBuilder();
        int indent = 1;
        for (String token : JavaTokens.tokenize(body)) {
            if (token.equals("}")) {
                printLine(line, indent);
                indent = Math.max(1, indent - 1);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(token);
            if (token.equals("{") || token.equals("}") || token.equals(";")) printLine(line, indent);
            if (token.equals("{")) indent++;
        }
        printLine(line, indent);
    }

    private static void printLine(StringBuilder line, int indent) {
        if (line.isEmpty()) return;
        System.out.println("    ".repeat(indent) + line);
        line.setLength(0);
    }
}
