package dev.amc.benchmark;

import dev.amc.core.AmcModel;
import dev.amc.core.ModelIO;
import dev.amc.core.Task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.SplittableRandom;

/** CPU wall-clock smoke benchmark for the default training configuration. */
public final class Benchmark {
    public static void main(String[] args) throws IOException {
        if (args.length != 0 && args.length != 2) {
            throw new IllegalArgumentException("Usage: Benchmark [memory-weights baseline-weights]");
        }
        var memory = new AmcModel(4, 4, 12, 2, true, 42);
        var baseline = new AmcModel(4, 4, 12, 2, false, 42);
        if (args.length == 2) {
            ModelIO.load(memory, Path.of(args[0]));
            ModelIO.load(baseline, Path.of(args[1]));
        }

        var rng = new SplittableRandom(123);
        var tasks = new Task[500];
        for (int i = 0; i < tasks.length; i++) tasks[i] = Task.random(rng, 4, 4, 6);
        int warmupPasses = 3;
        for (int i = 0; i < warmupPasses; i++) {
            predict(memory, tasks, 1);
            predict(baseline, tasks, 1);
        }

        int passes = 5;
        System.out.printf(Locale.ROOT, "weights=%s parametersPerModel=%d tasks=%d warmupPasses=%d passes=%d%n",
                args.length == 2 ? "checkpoint" : "initial", memory.parameterCount(), tasks.length,
                warmupPasses, passes);
        print("exact-address", predict(memory, tasks, passes));
        print("last-write", predict(baseline, tasks, passes));
    }

    static Measurement predict(AmcModel model, Task[] tasks, int passes) {
        long checksum = 0;
        long correct = 0;
        long start = System.nanoTime();
        for (int pass = 0; pass < passes; pass++) {
            for (Task task : tasks) {
                int prediction = model.predict(task);
                checksum += prediction;
                if (prediction == task.targetValue()) correct++;
            }
        }
        return new Measurement((long) tasks.length * passes, System.nanoTime() - start, correct, checksum);
    }

    private static void print(String mode, Measurement measurement) {
        System.out.printf(Locale.ROOT,
                "mode=%s predictions=%d accuracy=%.4f elapsedMs=%.3f usPerExample=%.3f checksum=%d%n",
                mode, measurement.samples(), (double) measurement.correct() / measurement.samples(),
                measurement.nanos() / 1e6, measurement.nanos() / 1e3 / measurement.samples(),
                measurement.checksum());
    }

    record Measurement(long samples, long nanos, long correct, long checksum) {}
}
