package dev.amc.benchmark;

import dev.amc.core.JavaLanguageModel;
import dev.amc.training.ProgramCorpus;
import dev.amc.training.TrainPrograms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                --select-class "$1" --fail-if-no-tests \
                '--config=junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition' || exit 11
            """;

    enum Result { INCOMPLETE, COMPILE_ERROR, FAILED_TESTS, TIMEOUT, PASSED }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: JavaProgramBenchmark [corpus] [weights | --initial | --reference] [test | validation | train]");
        }
        Path corpus = args.length > 0 ? Path.of(args[0]) : TrainPrograms.DEFAULT_CORPUS;
        String mode = args.length > 1 ? args[1] : TrainPrograms.DEFAULT_CHECKPOINT.toString();
        boolean reference = mode.equals("--reference");
        String split = args.length > 2 ? args[2] : "test";
        if (!reference && split.equals("train")) throw new IllegalArgumentException("Train split is for reference checks");
        var tasks = split.equals("train") ? ProgramCorpus.trainingReferences(corpus)
                : ProgramCorpus.evaluation(corpus, split);
        var model = reference ? null : mode.equals("--initial") ? TrainPrograms.initialize(corpus, 42)
                : JavaLanguageModel.load(Path.of(mode));
        if (!reference && !model.sourceMode()) throw new IllegalArgumentException("A source-mode checkpoint is required");
        requireImage();

        var counts = new EnumMap<Result, Integer>(Result.class);
        for (var task : tasks) {
            var decoded = reference ? null : model.decode(task.example().prompt(), ProgramCorpus.MAX_TOKENS);
            var candidate = reference ? new JavaLanguageModel.Generation(task.example().body(), true)
                    : decoded.generation();
            Result result = candidate.complete() ? evaluate(candidate.body(), task, corpus.resolve("lib")) : Result.INCOMPLETE;
            counts.merge(result, 1, Integer::sum);
            if (reference) {
                System.out.println(task.id() + " " + result);
            } else {
                System.out.printf("%s %s outputTokens=%d referenceTokens=%d%n", task.id(), result,
                        decoded.tokens(), model.tokenCount(task.example().body()));
            }
        }
        System.out.printf("mode=%s split=%s tasks=%d passed=%d %s%n", mode, split, tasks.size(),
                counts.getOrDefault(Result.PASSED, 0), counts);
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
            var process = run(List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                    "--network", "none", "--read-only", "--user", "65534:65534", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--memory", "320m", "--cpus", "1", "--pids-limit", "96",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777",
                    "--mount", "type=bind,source=" + input + ",target=/input,readonly",
                    "--entrypoint", "/bin/sh", IMAGE, "-c", junit == null ? COMMAND : JUNIT_COMMAND,
                    "amc-verify", junit == null ? "" : junit.testClass()));
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) return Result.TIMEOUT;
                return switch (process.exitValue()) {
                    case 0 -> Result.PASSED;
                    case 10 -> Result.COMPILE_ERROR;
                    case 11 -> Result.FAILED_TESTS;
                    default -> throw new IOException("Docker or container failed with code " + process.exitValue());
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
