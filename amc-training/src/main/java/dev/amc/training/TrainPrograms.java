package dev.amc.training;

import dev.amc.core.JavaLanguageModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class TrainPrograms {
    public static final Path DEFAULT_CORPUS = Path.of("datasets/java-programs");
    public static final Path DEFAULT_CHECKPOINT = Path.of("results/java-programs.bin");
    private static final Path DEFAULT_BPE_CHECKPOINT = Path.of("results/java-programs-bpe.bin");
    public static final Path DEFAULT_SCAFFOLD_CHECKPOINT = Path.of("results/java-programs-scaffold.bin");
    public static final Path DEFAULT_BODY_CHECKPOINT = Path.of("results/java-programs-body.bin");
    private static final Path DEFAULT_ATTENTION_CHECKPOINT = Path.of("results/java-programs-attention.bin");
    private static final Path DEFAULT_SCAFFOLD_ATTENTION_CHECKPOINT = Path.of("results/java-programs-scaffold-attention.bin");
    private static final int WIDTH = 48;
    private static final double LEARNING_RATE = 0.001;
    private static final int BPE_MERGES = 128;

    public static JavaLanguageModel initialize(Path corpus, long seed) throws IOException {
        return JavaLanguageModel.initializeSource(training(corpus), WIDTH, seed, LEARNING_RATE);
    }

    public static JavaLanguageModel initializeScaffold(Path corpus, long seed) throws IOException {
        return JavaLanguageModel.initializeSubwords(scaffoldTraining(corpus, false), WIDTH, seed, LEARNING_RATE, BPE_MERGES);
    }

    public static JavaLanguageModel initializeBody(Path corpus, long seed) throws IOException {
        return JavaLanguageModel.initializeSubwords(scaffoldTraining(corpus, true), WIDTH, seed, LEARNING_RATE, BPE_MERGES);
    }

    private static List<JavaLanguageModel.Example> training(Path corpus) throws IOException {
        return ProgramCorpus.training(corpus).stream().map(ProgramCorpus.Task::example).toList();
    }

    private static List<JavaLanguageModel.Example> scaffoldTraining(Path corpus, boolean body) throws IOException {
        var examples = new ArrayList<JavaLanguageModel.Example>();
        for (var task : ProgramCorpus.training(corpus)) {
            try {
                var scaffold = task.scaffold();
                String reference = task.example().body();
                String prompt = task.example().prompt();
                if (body) {
                    String implementation = scaffold.extractBody(reference);
                    scaffold.applyBody(implementation);
                    examples.add(new JavaLanguageModel.Example(scaffold.bodyPrompt(prompt), implementation));
                } else {
                    String implementation = scaffold.extract(reference);
                    scaffold.apply(implementation);
                    examples.add(new JavaLanguageModel.Example(scaffold.prompt(prompt), implementation));
                }
            } catch (IllegalArgumentException ex) {
                if (body) throw new IllegalArgumentException("Body corpus task " + task.id() + ": " + ex.getMessage(), ex);
                System.out.println("scaffoldSkipped=" + task.id() + " reason=" + ex.getMessage());
            }
        }
        return examples;
    }

    public static void main(String[] args) throws IOException {
        var flags = Arrays.stream(args).takeWhile(arg -> arg.startsWith("--")).toList();
        boolean scaffold = flags.contains("--scaffold");
        boolean plainBpe = flags.contains("--bpe");
        boolean body = flags.contains("--body");
        int formats = (scaffold ? 1 : 0) + (plainBpe ? 1 : 0) + (body ? 1 : 0);
        boolean bpe = formats > 0;
        boolean attention = flags.contains("--attention");
        args = Arrays.copyOfRange(args, flags.size(), args.length);
        if (args.length > 6 || args.length == 5 || formats > 1 || (attention && !bpe)
                || flags.size() != (attention ? 1 : 0) + formats) {
            throw new IllegalArgumentException(
                    "Usage: TrainPrograms [--bpe | --scaffold | --body] [--attention]"
                            + " [corpus] [weights] [epochs] [seed] [snapshot-directory snapshot-interval]");
        }
        Path corpus = args.length > 0 ? Path.of(args[0]) : DEFAULT_CORPUS;
        Path defaultCheckpoint = scaffold ? DEFAULT_SCAFFOLD_CHECKPOINT : bpe ? DEFAULT_BPE_CHECKPOINT : DEFAULT_CHECKPOINT;
        if (attention) defaultCheckpoint = scaffold ? DEFAULT_SCAFFOLD_ATTENTION_CHECKPOINT : DEFAULT_ATTENTION_CHECKPOINT;
        if (body) defaultCheckpoint = attention ? Path.of("results/java-programs-body-attention.bin") : DEFAULT_BODY_CHECKPOINT;
        Path checkpoint = args.length > 1 ? Path.of(args[1]) : defaultCheckpoint;
        int epochs = args.length > 2 ? Integer.parseInt(args[2]) : 600;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 42;
        if (epochs < 1) throw new IllegalArgumentException("epochs must be positive");
        int snapshotInterval = args.length == 6 ? Integer.parseInt(args[5]) : epochs;
        if (snapshotInterval < 1 || epochs % snapshotInterval != 0) {
            throw new IllegalArgumentException("Snapshot interval must divide the number of epochs");
        }
        Path snapshots = args.length == 6 ? Files.createDirectory(Path.of(args[4])) : null;
        var examples = new ArrayList<>(scaffold || body ? scaffoldTraining(corpus, body) : training(corpus));
        var model = bpe ? JavaLanguageModel.initializeSubwords(examples, WIDTH, seed, LEARNING_RATE, BPE_MERGES, attention)
                : JavaLanguageModel.initializeSource(examples, WIDTH, seed, LEARNING_RATE);
        var random = new Random(seed);
        int trainingTokens = 0;
        for (var example : examples) trainingTokens += model.tokenCount(example.body());
        System.out.printf(Locale.ROOT,
                "tokenizer=%s scaffold=%s body=%s attention=%s examples=%d sourceTokens=%d parameters=%d epochs=%d seed=%d learningRate=%.4f%n",
                bpe ? "bpe" : "character", scaffold, body, attention, examples.size(), trainingTokens, model.parameterCount(),
                epochs, seed, LEARNING_RATE);
        Path bestWeights = Files.createTempFile("amc-program-training-", ".bin");
        double bestLoss = Double.POSITIVE_INFINITY;
        int bestEpoch = 0;
        try {
            for (int index = 0; index < epochs; index++) {
                int epoch = index + 1;
                Collections.shuffle(examples, random);
                double loss = 0;
                for (var example : examples) loss += model.train(example);
                double meanLoss = loss / examples.size();
                if (snapshots != null && epoch % snapshotInterval == 0) {
                    long updates = (long) epoch * examples.size();
                    model.save(snapshots.resolve("updates-" + updates + ".bin"));
                    System.out.printf(Locale.ROOT, "snapshotUpdates=%d meanTokenLoss=%.6f%n", updates, meanLoss);
                }
                if (epoch == 1 || epoch % Math.max(1, epochs / 24) == 0 || epoch == epochs) {
                    if (meanLoss < bestLoss) {
                        bestLoss = meanLoss;
                        bestEpoch = epoch;
                        model.save(bestWeights);
                    }
                    System.out.printf(Locale.ROOT, "epoch=%d meanTokenLoss=%.6f%n", epoch, meanLoss);
                }
            }
            model = JavaLanguageModel.load(bestWeights);
        } finally {
            Files.deleteIfExists(bestWeights);
        }
        model.save(checkpoint);
        System.out.printf(Locale.ROOT, "selectedEpoch=%d meanTokenLoss=%.6f weights=%s%n", bestEpoch, bestLoss, checkpoint);
        int exact = 0;
        for (var example : examples) {
            var generated = model.generate(example.prompt(), ProgramCorpus.MAX_TOKENS);
            if (generated.complete() && generated.body().equals(example.body())) exact++;
        }
        System.out.printf("exactTrainingSources=%d/%d%n", exact, examples.size());
    }
}
