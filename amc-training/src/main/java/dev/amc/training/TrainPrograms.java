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
    private static final int WIDTH = 48;
    private static final double LEARNING_RATE = 0.001;
    private static final int BPE_MERGES = 128;

    public static JavaLanguageModel initialize(Path corpus, long seed) throws IOException {
        return JavaLanguageModel.initializeSource(training(corpus), WIDTH, seed, LEARNING_RATE);
    }

    private static List<JavaLanguageModel.Example> training(Path corpus) throws IOException {
        return ProgramCorpus.training(corpus).stream().map(ProgramCorpus.Task::example).toList();
    }

    public static void main(String[] args) throws IOException {
        boolean bpe = args.length > 0 && args[0].equals("--bpe");
        if (bpe) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length > 4) {
            throw new IllegalArgumentException("Usage: TrainPrograms [--bpe] [corpus] [weights] [epochs] [seed]");
        }
        Path corpus = args.length > 0 ? Path.of(args[0]) : DEFAULT_CORPUS;
        Path checkpoint = args.length > 1 ? Path.of(args[1]) : (bpe ? DEFAULT_BPE_CHECKPOINT : DEFAULT_CHECKPOINT);
        int epochs = args.length > 2 ? Integer.parseInt(args[2]) : 600;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 42;
        if (epochs < 1) throw new IllegalArgumentException("epochs must be positive");
        var examples = new ArrayList<>(training(corpus));
        var model = bpe ? JavaLanguageModel.initializeSubwords(examples, WIDTH, seed, LEARNING_RATE, BPE_MERGES)
                : JavaLanguageModel.initializeSource(examples, WIDTH, seed, LEARNING_RATE);
        var random = new Random(seed);
        int trainingTokens = 0;
        for (var example : examples) trainingTokens += model.tokenCount(example.body());
        System.out.printf(Locale.ROOT,
                "tokenizer=%s examples=%d sourceTokens=%d parameters=%d epochs=%d seed=%d learningRate=%.4f%n",
                bpe ? "bpe" : "character", examples.size(), trainingTokens, model.parameterCount(), epochs, seed,
                LEARNING_RATE);
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
