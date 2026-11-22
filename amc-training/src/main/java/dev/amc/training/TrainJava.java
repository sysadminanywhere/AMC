package dev.amc.training;

import dev.amc.core.JavaLanguageModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

public final class TrainJava {
    public static void main(String[] args) throws IOException {
        var invocation = JavaProblems.Invocation.parse(args);
        args = invocation.arguments();
        if (args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: TrainJava [--all | --arrays | --strings | --collections] [weights-file] [epochs] [seed]");
        }
        Path checkpoint = Path.of(args.length > 0 ? args[0] : invocation.checkpoint());
        int epochs = args.length > 1 ? Integer.parseInt(args[1]) : 80;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42;
        if (epochs < 1) throw new IllegalArgumentException("epochs must be positive");

        var examples = new ArrayList<>(invocation.training());
        double learningRate = invocation.domains().size() > 1 ? 0.001 : JavaLanguageModel.DEFAULT_LEARNING_RATE;
        var model = JavaLanguageModel.initialize(examples, invocation.width(), seed, learningRate);
        var random = new Random(seed);
        System.out.printf(Locale.ROOT, "domains=%s trainingExamples=%d parameters=%d epochs=%d seed=%d learningRate=%.4f%n",
                invocation.domains(), examples.size(), model.parameterCount(), epochs, seed, learningRate);
        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(examples, random);
            double loss = 0;
            for (var example : examples) loss += model.train(example);
            if (epoch == 0 || (epoch + 1) % Math.max(1, epochs / 8) == 0 || epoch + 1 == epochs) {
                System.out.printf(Locale.ROOT, "epoch=%d trainingTokenLoss=%.6f%n",
                        epoch + 1, loss / examples.size());
            }
        }
        model.save(checkpoint);
        System.out.println("Saved Java language model to " + checkpoint);
    }
}
