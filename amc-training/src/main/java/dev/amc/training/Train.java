package dev.amc.training;

import dev.amc.core.Adam;
import dev.amc.core.AmcModel;
import dev.amc.core.ModelIO;
import dev.amc.core.Scalar;
import dev.amc.core.Task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public final class Train {
    private static final Set<String> OPTIONS = Set.of("epochs", "keys", "values", "hidden",
            "steps", "writes", "seed", "save", "load", "test-samples");

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        var model = new AmcModel(options.keys(), options.values(), options.hidden(),
                options.steps(), options.memory(), options.seed());
        if (options.load() != null) ModelIO.load(model, Path.of(options.load()));

        var seeds = new SplittableRandom(options.seed());
        var training = seeds.split();
        long validationSeed = seeds.nextLong();
        long testSeed = seeds.nextLong();
        System.out.printf(Locale.ROOT,
                "mode=%s parameters=%d updates=%d seed=%d keys=%d values=%d writes=%d hidden=%d recurrentSteps=%d%n",
                options.memory() ? "exact-address" : "last-write", model.parameterCount(),
                options.epochs(), options.seed(), options.keys(), options.values(), options.writes(),
                options.hidden(), options.steps());

        var parameters = model.parameters();
        var optimizer = new Adam(parameters, 0.015);
        int reportEvery = Math.max(1, options.epochs() / 10);
        printValidation(0, model, options, validationSeed);
        for (int i = 0; i < options.epochs(); i++) {
            Task task = Task.random(training, options.keys(), options.values(), options.writes());
            update(model, parameters, optimizer, task);
            int step = i + 1;
            if (step % reportEvery == 0 || step == options.epochs()) {
                printValidation(step, model, options, validationSeed);
            }
        }

        var test = evaluate(model, options.keys(), options.values(), options.writes(), testSeed,
                options.testSamples());
        System.out.printf(Locale.ROOT,
                "testSamples=%d testAccuracy=%.4f testLoss=%.6f lastWriteAccuracy=%.4f%n",
                options.testSamples(), test.accuracy(), test.loss(), test.lastWriteAccuracy());
        if (options.save() != null) {
            ModelIO.save(model, Path.of(options.save()));
            System.out.println("Saved weights to " + options.save());
        }
    }

    static void update(AmcModel model, List<Scalar> parameters, Adam optimizer, Task task) {
        // Reverse-mode traversal only visits parameters connected to the current loss.
        for (Scalar parameter : parameters) parameter.grad = 0;
        Scalar loss = model.loss(task);
        if (!Double.isFinite(loss.value)) throw new ArithmeticException("Non-finite training loss");
        loss.backward();
        optimizer.update();
    }

    private static void printValidation(int step, AmcModel model, Options options, long seed) {
        var result = evaluate(model, options.keys(), options.values(), options.writes(), seed, 200);
        System.out.printf(Locale.ROOT, "step=%d validationAccuracy=%.4f validationLoss=%.6f%n",
                step, result.accuracy(), result.loss());
    }

    public record Result(double accuracy, double loss, double lastWriteAccuracy) {}

    public static Result evaluate(AmcModel model, int keys, int values, int writes, long seed, int samples) {
        if (samples < 1) throw new IllegalArgumentException("samples must be positive");
        var rng = new SplittableRandom(seed);
        int correct = 0;
        int lastWriteCorrect = 0;
        double loss = 0;
        for (int i = 0; i < samples; i++) {
            Task task = Task.random(rng, keys, values, writes);
            var score = model.score(task);
            if (score.prediction() == task.targetValue()) correct++;
            if (task.assignments().getLast().value() == task.targetValue()) lastWriteCorrect++;
            loss += score.loss();
        }
        return new Result((double) correct / samples, loss / samples, (double) lastWriteCorrect / samples);
    }

    record Options(int epochs, int keys, int values, int hidden, int steps, int writes,
                   long seed, boolean memory, String save, String load, int testSamples) {
        static Options parse(String[] args) {
            var options = new HashMap<String, String>();
            boolean memory = true;
            for (String arg : args) {
                if (arg.equals("--baseline") && memory) {
                    memory = false;
                    continue;
                }
                int equals = arg.indexOf('=');
                if (!arg.startsWith("--") || equals < 3 || equals == arg.length() - 1) {
                    throw new IllegalArgumentException("Expected --name=value or --baseline: " + arg);
                }
                String name = arg.substring(2, equals);
                if (!OPTIONS.contains(name) || options.putIfAbsent(name, arg.substring(equals + 1)) != null) {
                    throw new IllegalArgumentException("Unknown or repeated option: --" + name);
                }
            }
            return new Options(integer(options, "epochs", 1200, 0), integer(options, "keys", 4, 2),
                    integer(options, "values", 4, 2), integer(options, "hidden", 12, 1),
                    integer(options, "steps", 2, 1), integer(options, "writes", 6, 2),
                    Long.parseLong(options.getOrDefault("seed", "42")), memory,
                    options.get("save"), options.get("load"), integer(options, "test-samples", 1000, 1));
        }

        private static int integer(Map<String, String> options, String name, int fallback, int minimum) {
            int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
            if (value < minimum) throw new IllegalArgumentException("--" + name + " must be >= " + minimum);
            return value;
        }
    }
}
