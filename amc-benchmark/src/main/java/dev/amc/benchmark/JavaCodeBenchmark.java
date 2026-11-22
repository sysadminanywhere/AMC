package dev.amc.benchmark;

import dev.amc.core.JavaLanguageModel;
import dev.amc.core.JavaTokens;
import dev.amc.training.JavaProblems;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public final class JavaCodeBenchmark {
    enum Result { INCOMPLETE, OUTSIDE_SUBSET, COMPILE_ERROR, WRONG_RESULT, TIMEOUT, PASSED }

    private static final Set<String> EXPRESSION_TOKENS = Set.of(
            "x", "y", "+", "-", "*", "(", ")", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    private static final Set<String> ARRAY_TOKENS = Set.of(
            "int", "result", "value", "values", "for", "if", "return", "=", "+=", ">", "<", ">=", "==",
            "+", "-", "*", ":", "(", ")", "{", "}", ";", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    private static final List<String> ARRAY_LOOP = List.of("for", "(", "int", "value", ":", "values", ")");
    private static final Set<String> STRING_TOKENS = Set.of("char", "ch", "text", ".", "toCharArray", "index");
    private static final List<String> STRING_LOOP =
            List.of("for", "(", "char", "ch", ":", "text", ".", "toCharArray", "(", ")", ")");
    private static final List<String> CHAR_ARRAY_CALL = List.of("text", ".", "toCharArray", "(", ")");
    private static final Set<String> COLLECTION_TOKENS =
            Set.of("Set", "HashSet", "Integer", "new", "seen", ".", "add", "&&");
    private static final List<String> SET_ADD_CALL = List.of("seen", ".", "add", "(", "value", ")");
    private static final List<String> SET_CONSTRUCTOR = List.of("new", "HashSet", "<", ">", "(", ")");

    public static void main(String[] args) throws IOException, InterruptedException {
        var invocation = JavaProblems.Invocation.parse(args);
        args = invocation.arguments();
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "Usage: JavaCodeBenchmark [--all | --arrays | --strings | --collections] [weights-file | --initial]");
        }
        var model = args.length == 1 && args[0].equals("--initial")
                ? JavaLanguageModel.initialize(invocation.training(), invocation.width(), 42)
                : JavaLanguageModel.load(Path.of(args.length == 0 ? invocation.checkpoint() : args[0]));
        if (model.sourceMode()) throw new IllegalArgumentException("Use JavaProgramBenchmark for a source-mode checkpoint");
        for (var domain : invocation.domains()) {
            System.out.println("domain=" + domain);
            evaluate(model, domain, JavaProblems.Split.NEW_INPUTS);
            evaluate(model, domain, JavaProblems.Split.NEW_FAMILIES);
        }
    }

    private static void evaluate(JavaLanguageModel model, JavaProblems.Domain domain, JavaProblems.Split split)
            throws IOException, InterruptedException {
        var problems = domain.problems(split);
        var counts = new EnumMap<Result, Integer>(Result.class);
        var families = new TreeMap<String, int[]>();
        for (var problem : problems) {
            var generated = model.generate(problem.example().prompt(), domain.maxTokens());
            Result result = check(generated, problem);
            counts.merge(result, 1, Integer::sum);
            int[] count = families.computeIfAbsent(problem.family(), family -> new int[2]);
            count[0]++;
            if (result == Result.PASSED) count[1]++;
        }
        int passed = counts.getOrDefault(Result.PASSED, 0);
        int compiled = passed + counts.getOrDefault(Result.WRONG_RESULT, 0) + counts.getOrDefault(Result.TIMEOUT, 0);
        System.out.printf(Locale.ROOT, "split=%s problems=%d compiled=%d passed=%d pass@1=%.4f %s%n",
                split, problems.size(), compiled, passed, (double) passed / problems.size(), counts);
        families.forEach((name, count) -> System.out.printf(Locale.ROOT, "  %s %d/%d%n", name, count[1], count[0]));
    }

    static Result check(JavaLanguageModel.Generation generated, JavaProblems.Problem problem)
            throws IOException, InterruptedException {
        if (!generated.complete()) return Result.INCOMPLETE;
        var tokens = JavaTokens.tokenize(generated.body());
        if (tokens.size() > problem.domain().maxTokens() || !allowed(tokens, problem.domain())) {
            return Result.OUTSIDE_SUBSET;
        }
        String body = String.join(" ", tokens);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Run the benchmark with a JDK");
        Path directory = Files.createTempDirectory("amc-java-eval-");
        try {
            Path source = directory.resolve("Verify.java");
            Files.writeString(source, source(body, problem));
            int status = compiler.run(null, OutputStream.nullOutputStream(), OutputStream.nullOutputStream(),
                    "-proc:none", "-encoding", "UTF-8", "--release", "21", "-classpath", directory.toString(),
                    "-d", directory.toString(), source.toString());
            if (status != 0) return Result.COMPILE_ERROR;

            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            if (!Files.isRegularFile(java)) java = java.resolveSibling("java.exe");
            Process process = new ProcessBuilder(java.toString(), "-Xmx32m", "-cp", directory.toString(), "Verify")
                    .directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) return Result.TIMEOUT;
                return process.exitValue() == 0 ? Result.PASSED : Result.WRONG_RESULT;
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static boolean allowed(List<String> tokens, JavaProblems.Domain domain) {
        if (domain == JavaProblems.Domain.ARITHMETIC) {
            return tokens.size() >= 3 && tokens.getFirst().equals("return") && tokens.getLast().equals(";")
                    && EXPRESSION_TOKENS.containsAll(tokens.subList(1, tokens.size() - 1));
        }
        if (tokens.isEmpty() || !tokens.stream().allMatch(token -> allowedToken(token, domain))) return false;
        // Loop headers and call sites must match the bounded operations for the selected domain.
        boolean strings = domain == JavaProblems.Domain.STRINGS;
        var loop = strings ? STRING_LOOP : ARRAY_LOOP;
        int loops = 0;
        for (int i = 0; i < tokens.size(); i++) {
            switch (tokens.get(i)) {
                case "for" -> {
                    if (++loops > 1 || !matchesAt(tokens, i, loop)) return false;
                }
                case "." -> {
                    if (!matchesAt(tokens, i - 1, strings ? CHAR_ARRAY_CALL : SET_ADD_CALL)) return false;
                }
                case "new" -> {
                    if (!matchesAt(tokens, i, SET_CONSTRUCTOR)) return false;
                }
            }
        }
        return true;
    }

    private static boolean allowedToken(String token, JavaProblems.Domain domain) {
        if (ARRAY_TOKENS.contains(token)) return true;
        return switch (domain) {
            case STRINGS -> STRING_TOKENS.contains(token) || isCharacterLiteral(token);
            case COLLECTIONS -> COLLECTION_TOKENS.contains(token);
            default -> false;
        };
    }

    private static boolean isCharacterLiteral(String token) {
        return token.length() == 3 && token.charAt(0) == '\'' && token.charAt(2) == '\''
                && token.charAt(1) >= 'a' && token.charAt(1) <= 'z';
    }

    private static boolean matchesAt(List<String> tokens, int start, List<String> allowed) {
        int end = start + allowed.size();
        return start >= 0 && end <= tokens.size() && tokens.subList(start, end).equals(allowed);
    }

    private static String source(String body, JavaProblems.Problem problem) {
        var verification = new StringBuilder();
        for (var check : problem.checks()) {
            verification.append("    if (Solution.solve(").append(check.arguments())
                    .append(") != ").append(check.expected()).append(") throw new AssertionError();\n");
        }
        return """
                import java.util.HashSet;
                import java.util.List;
                import java.util.Set;

                final class Solution {
                    %s {
                        %s
                    }
                }
                public final class Verify {
                    public static void main(String[] args) {
                %s    }
                }
                """.formatted(problem.domain().signature(), body, verification);
    }
}
