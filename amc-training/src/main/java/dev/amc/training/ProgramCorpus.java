package dev.amc.training;

import dev.amc.core.JavaLanguageModel;
import dev.amc.core.JavaTokens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Directory-backed corpus for complete Java source files and their independent checks. */
public final class ProgramCorpus {
    public static final int MAX_TOKENS = 2048;
    private static final int MAX_FILE_BYTES = 32_768;
    private static final int MAX_TEST_BYTES = 131_072;

    private ProgramCorpus() {}

    public record JUnitFiles(String sourceName, String testName) {
        public String testClass() { return testName.substring(0, testName.length() - ".java".length()); }
    }

    public record Task(String id, JavaLanguageModel.Example example, String verification, JUnitFiles junit,
                       Map<String, String> support, String starter) {
        public JavaScaffold scaffold() {
            if (starter == null) throw new IllegalArgumentException("Task has no Starter.java");
            return new JavaScaffold(starter);
        }
    }

    public static List<Task> training(Path root) throws IOException {
        return load(root.resolve("train"), false);
    }

    public static List<Task> evaluation(Path root) throws IOException {
        return evaluation(root, "test");
    }

    public static List<Task> trainingReferences(Path root) throws IOException {
        return load(root.resolve("train"), true);
    }

    public static List<Task> evaluation(Path root, String split) throws IOException {
        if (!split.equals("test") && !split.equals("validation")) {
            throw new IllegalArgumentException("Expected test or validation split");
        }
        var heldOut = load(root.resolve(split), true);
        requireDistinct(training(root), heldOut);
        if (split.equals("test") && Files.isDirectory(root.resolve("validation"))) {
            var validation = load(root.resolve("validation"), true);
            requireDistinct(training(root), validation);
            requireDistinct(validation, heldOut);
        }
        return heldOut;
    }

    private static void requireDistinct(List<Task> source, List<Task> heldOut) throws IOException {
        var ids = new HashSet<String>();
        var prompts = new HashSet<String>();
        var sources = new HashSet<List<String>>();
        for (Task task : source) {
            ids.add(task.id());
            prompts.add(task.example().prompt());
            sources.add(JavaTokens.tokenize(task.example().body()));
        }
        for (Task task : heldOut) {
            if (ids.contains(task.id()) || prompts.contains(task.example().prompt())
                    || sources.contains(JavaTokens.tokenize(task.example().body()))) {
                throw new IOException("Corpus split overlap: " + task.id());
            }
        }
    }

    private static List<Task> load(Path split, boolean verificationRequired) throws IOException {
        var tasks = new ArrayList<Task>();
        try (var directories = Files.list(split)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                String source = read(directory.resolve("Solution.java"));
                if (JavaTokens.sourceTokens(source).size() >= MAX_TOKENS) {
                    throw new IOException("Solution exceeds source token limit: " + directory);
                }
                String prompt = readPrompt(directory.resolve("prompt.txt"));
                String verification = verificationRequired ? read(directory.resolve("Verify.java"), MAX_TEST_BYTES) : "";
                Path junitFile = directory.resolve("junit.txt");
                JUnitFiles junit = Files.exists(junitFile) ? junitFiles(junitFile) : null;
                var support = verificationRequired ? readSupport(directory.resolve("support"), junit) : Map.<String, String>of();
                Path starterFile = directory.resolve("Starter.java");
                String starter = Files.exists(starterFile) ? read(starterFile) : null;
                tasks.add(new Task(directory.getFileName().toString(),
                        new JavaLanguageModel.Example(prompt, source), verification, junit, support, starter));
            }
        }
        if (tasks.isEmpty()) throw new IOException("No tasks in " + split);
        return List.copyOf(tasks);
    }

    private static JUnitFiles junitFiles(Path path) throws IOException {
        var names = read(path).lines().toList();
        if (names.size() != 2 || names.getFirst().equals(names.getLast())
                || names.stream().anyMatch(name -> !isJavaFilename(name))) {
            throw new IOException("Expected two distinct Java filenames in " + path);
        }
        return new JUnitFiles(names.getFirst(), names.getLast());
    }

    private static Map<String, String> readSupport(Path directory, JUnitFiles junit) throws IOException {
        if (!Files.exists(directory)) return Map.of();
        var reserved = junit == null ? List.of("Solution.java", "Verify.java", "RunVerification.java")
                : List.of(junit.sourceName(), junit.testName());
        var result = new LinkedHashMap<String, String>();
        try (var entries = Files.list(directory)) {
            for (Path path : entries.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!Files.isRegularFile(path) || !isJavaFilename(name)
                        || reserved.stream().anyMatch(name::equalsIgnoreCase)) {
                    throw new IOException("Invalid support source: " + path);
                }
                result.put(name, read(path));
            }
        }
        return Map.copyOf(result);
    }

    private static boolean isJavaFilename(String name) {
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*\\.java");
    }

    public static String readPrompt(Path path) throws IOException {
        return read(path).strip();
    }

    private static String read(Path path) throws IOException {
        return read(path, MAX_FILE_BYTES);
    }

    private static String read(Path path, int maxBytes) throws IOException {
        if (Files.size(path) > maxBytes) throw new IOException("Corpus file exceeds byte limit: " + path);
        String value = Files.readString(path).replace("\r\n", "\n");
        if (value.isBlank()) throw new IOException("Empty corpus file: " + path);
        return value;
    }
}
