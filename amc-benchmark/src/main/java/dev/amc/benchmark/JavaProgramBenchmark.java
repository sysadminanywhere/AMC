package dev.amc.benchmark;

import dev.amc.core.JavaLanguageModel;
import dev.amc.training.JavaScaffold;
import dev.amc.training.ProgramCorpus;
import dev.amc.training.TrainPrograms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Compiles and runs complete Java sources in disposable Linux containers. */
public final class JavaProgramBenchmark {
    private static final String IMAGE = "eclipse-temurin:21-jdk";
    private static final String VERIFY = """
            public class RunVerification {
                public static void main(String[] args) throws Exception {
                    Verify.main(args);
                    java.nio.file.Files.createFile(java.nio.file.Path.of("/tmp/checks-passed"));
                }
            }
            """;
    private static final String COMMAND = """
            mkdir /tmp/classes &&
            javac -encoding UTF-8 -proc:none --release 21 -d /tmp/classes /input/*.java || exit 10
            java -Xmx96m -cp /tmp/classes RunVerification || exit 11
            test -f /tmp/checks-passed || exit 11
            """;
    private static final String JUNIT_COMMAND = """
            mkdir /tmp/classes &&
            javac -encoding UTF-8 -proc:none --release 21 -cp '/input/*' -d /tmp/classes /input/*.java || exit 10
            java -Xmx96m -jar /input/junit.jar execute --class-path /tmp/classes:/input/assertj.jar \
                --select-class "$1" --fail-if-no-tests --disable-ansi-colors --disable-banner --details=summary \
                '--config=junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition' || exit 11
            """;
    private static final String DIAGNOSTIC_COMMAND = """
            (
            %s
            ) > /tmp/amc-diagnostics 2>&1
            status=$?
            if [ "$status" -ne 0 ]; then head -c 4096 /tmp/amc-diagnostics; fi
            exit "$status"
            """;

    enum Result { INCOMPLETE, REPETITION, UNSUPPORTED_SCAFFOLD, INVALID_EDIT, COMPILE_ERROR, FAILED_TESTS, TIMEOUT, PASSED }

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = Arrays.stream(args).takeWhile(arg -> arg.startsWith("--")).toList();
        boolean stopOnRepeat = flags.contains("--stop-on-repeat");
        boolean scaffoldMode = flags.contains("--scaffold");
        boolean bodyMode = flags.contains("--body");
        boolean candidateMode = flags.contains("--candidates");
        boolean feedback = flags.contains("--feedback");
        if ((scaffoldMode && bodyMode) || flags.stream().anyMatch(flag -> !flag.equals("--scaffold") && !flag.equals("--stop-on-repeat")
                && !flag.equals("--body")
                && !flag.equals("--candidates") && !flag.equals("--feedback"))) {
            throw new IllegalArgumentException("Unknown benchmark option");
        }
        args = Arrays.copyOfRange(args, flags.size(), args.length);
        if ((candidateMode && (args.length < 2 || args.length > 3 || scaffoldMode || bodyMode || stopOnRepeat))
                || (feedback && !candidateMode)) {
            throw new IllegalArgumentException(
                    "Usage: JavaProgramBenchmark --candidates [--feedback] corpus candidate-directory [test | validation]");
        }
        if (args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: JavaProgramBenchmark [--scaffold | --body] [--stop-on-repeat] [corpus] [weights | --initial | --reference]"
                            + " [test | validation | train]");
        }
        Path corpus = args.length > 0 ? Path.of(args[0]) : TrainPrograms.DEFAULT_CORPUS;
        Path defaultCheckpoint = bodyMode ? TrainPrograms.DEFAULT_BODY_CHECKPOINT
                : scaffoldMode ? TrainPrograms.DEFAULT_SCAFFOLD_CHECKPOINT : TrainPrograms.DEFAULT_CHECKPOINT;
        String mode = args.length > 1 ? args[1] : defaultCheckpoint.toString();
        boolean reference = !candidateMode && mode.equals("--reference");
        String split = args.length > 2 ? args[2] : "test";
        if (!reference && split.equals("train")) throw new IllegalArgumentException("Train split is for reference checks");
        var tasks = split.equals("train") ? ProgramCorpus.trainingReferences(corpus)
                : ProgramCorpus.evaluation(corpus, split);
        Path candidates = candidateMode ? Path.of(mode) : null;
        if (candidateMode && !Files.isDirectory(candidates)) throw new IOException("Missing candidate directory: " + candidates);
        var model = reference || candidateMode ? null : mode.equals("--initial")
                ? (bodyMode ? TrainPrograms.initializeBody(corpus, 42)
                    : scaffoldMode ? TrainPrograms.initializeScaffold(corpus, 42) : TrainPrograms.initialize(corpus, 42))
                : JavaLanguageModel.load(Path.of(mode));
        if (model != null && !model.sourceMode()) throw new IllegalArgumentException("A source-mode checkpoint is required");
        requireImage();

        var counts = new EnumMap<Result, Integer>(Result.class);
        for (var task : tasks) {
            Result result = candidateMode ? evaluateFile(task, candidates, corpus.resolve("lib"), feedback)
                    : evaluateTask(task, model, scaffoldMode || bodyMode, bodyMode, stopOnRepeat, corpus.resolve("lib"));
            counts.merge(result, 1, Integer::sum);
        }
        System.out.printf("mode=%s split=%s tasks=%d passed=%d %s%n", mode, split, tasks.size(),
                counts.getOrDefault(Result.PASSED, 0), counts);
    }

    private static Result evaluateFile(ProgramCorpus.Task task, Path directory, Path libraries, boolean feedback)
            throws IOException, InterruptedException {
        Path source = directory.resolve(task.id() + ".java");
        Path incomplete = directory.resolve(task.id() + ".incomplete");
        boolean hasSource = Files.isRegularFile(source);
        boolean hasIncomplete = Files.isRegularFile(incomplete);
        if (hasSource == hasIncomplete) throw new IOException("Expected exactly one candidate output for " + task.id());
        if (hasSource && Files.size(source) > 131_072) throw new IOException("Candidate source is too large: " + source);
        Path diagnostics = feedback ? Files.createTempFile("amc-java-diagnostics-", ".txt") : null;
        try {
            Result result = hasSource ? evaluate(Files.readString(source), task, libraries, diagnostics) : Result.INCOMPLETE;
            if (feedback) {
                String output = new String(Files.readAllBytes(diagnostics), StandardCharsets.UTF_8);
                Files.writeString(directory.resolve(task.id() + ".feedback.txt"), result + "\n" + output);
            }
            System.out.println(task.id() + " " + result);
            return result;
        } finally {
            if (diagnostics != null) Files.deleteIfExists(diagnostics);
        }
    }

    private static Result evaluateTask(ProgramCorpus.Task task, JavaLanguageModel model, boolean scaffoldMode,
                                       boolean bodyMode, boolean stopOnRepeat, Path libraries)
            throws IOException, InterruptedException {
        JavaScaffold scaffold = null;
        String reference = task.example().body();
        String prompt = task.example().prompt();
        if (scaffoldMode) {
            try {
                scaffold = task.scaffold();
                prompt = bodyMode ? scaffold.bodyPrompt(prompt) : scaffold.prompt(prompt);
                if (model == null) reference = bodyMode ? scaffold.applyBody(scaffold.extractBody(reference))
                        : scaffold.apply(scaffold.extract(reference));
            } catch (IllegalArgumentException ex) {
                System.out.println(task.id() + " " + Result.UNSUPPORTED_SCAFFOLD + " " + ex.getMessage());
                return Result.UNSUPPORTED_SCAFFOLD;
            }
        }
        if (model == null) {
            Result result = evaluate(reference, task, libraries);
            System.out.println(task.id() + " " + result);
            return result;
        }

        var decoded = model.decode(prompt, ProgramCorpus.MAX_TOKENS, stopOnRepeat);
        Result result;
        if (!decoded.generation().complete()) {
            result = decoded.stop() == JavaLanguageModel.Stop.REPETITION ? Result.REPETITION : Result.INCOMPLETE;
        } else {
            String generated = decoded.generation().body();
            if (scaffold != null) {
                try {
                    generated = bodyMode ? scaffold.applyBody(generated) : scaffold.apply(generated);
                } catch (IllegalArgumentException ex) {
                    System.out.printf("%s %s outputTokens=%d %s%n", task.id(), Result.INVALID_EDIT,
                            decoded.tokens(), ex.getMessage());
                    return Result.INVALID_EDIT;
                }
            }
            result = evaluate(generated, task, libraries);
        }
        String referenceTokens = scaffold == null ? " referenceTokens=" + model.tokenCount(reference) : "";
        System.out.printf("%s %s outputTokens=%d%s%n", task.id(), result, decoded.tokens(), referenceTokens);
        return result;
    }

    private static void requireImage() throws IOException, InterruptedException {
        Process inspect = run(List.of("docker", "image", "inspect", IMAGE));
        if (!inspect.waitFor(15, TimeUnit.SECONDS)) {
            inspect.destroyForcibly().waitFor();
            throw new IOException("Docker image check timed out");
        }
        if (inspect.exitValue() != 0) throw new IOException("Linux Docker with a local " + IMAGE + " image is required");
    }

    private static Result evaluate(String candidate, ProgramCorpus.Task task, Path libraries)
            throws IOException, InterruptedException {
        return evaluate(candidate, task, libraries, null);
    }

    private static Result evaluate(String candidate, ProgramCorpus.Task task, Path libraries, Path diagnostics)
            throws IOException, InterruptedException {
        Path input = Files.createTempDirectory("amc-java-source-");
        String name = "amc-source-" + UUID.randomUUID();
        try {
            var junit = task.junit();
            if (junit == null) {
                Files.writeString(input.resolve("Solution.java"), candidate);
                Files.writeString(input.resolve("Verify.java"), task.verification());
                Files.writeString(input.resolve("RunVerification.java"), VERIFY);
            } else {
                Files.writeString(input.resolve(junit.sourceName()), candidate);
                Files.writeString(input.resolve(junit.testName()), task.verification());
                Files.copy(libraries.resolve("junit.jar"), input.resolve("junit.jar"));
                Files.copy(libraries.resolve("assertj.jar"), input.resolve("assertj.jar"));
            }
            for (var file : task.support().entrySet()) {
                Files.writeString(input.resolve(file.getKey()), file.getValue());
            }
            String verification = junit == null ? COMMAND : JUNIT_COMMAND;
            if (diagnostics != null) verification = DIAGNOSTIC_COMMAND.formatted(verification);
            var command = List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                    "--network", "none", "--read-only", "--user", "65534:65534", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--memory", "320m", "--cpus", "1", "--pids-limit", "96",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777",
                    "--mount", "type=bind,source=" + input + ",target=/input,readonly",
                    "--entrypoint", "/bin/sh", IMAGE, "-c", verification,
                    "amc-verify", junit == null ? "" : junit.testClass());
            var process = diagnostics == null ? run(command) : new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(diagnostics.toFile()).start();
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) return Result.TIMEOUT;
                return switch (process.exitValue()) {
                    case 0 -> Result.PASSED;
                    case 10 -> Result.COMPILE_ERROR;
                    case 11 -> Result.FAILED_TESTS;
                    default -> throw new IOException("Docker or container failed with code " + process.exitValue()
                            + (diagnostics == null ? "" : "\n" + Files.readString(diagnostics)));
                };
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly().waitFor();
                    Process cleanup = run(List.of("docker", "rm", "-f", name));
                    if (!cleanup.waitFor(10, TimeUnit.SECONDS)) cleanup.destroyForcibly().waitFor();
                }
            }
        } finally {
            try (var files = Files.list(input)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(input);
        }
    }

    private static Process run(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
}
