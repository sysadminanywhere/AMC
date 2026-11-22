package dev.amc.benchmark;

import dev.amc.core.AmcModel;
import dev.amc.core.Task;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTest {
    @Test
    void measuredPassesAccountForEveryPrediction() {
        var model = new AmcModel(4, 4, 12, 2, true, 42);
        var rng = new SplittableRandom(123);
        var tasks = new Task[]{Task.random(rng, 4, 4, 6), Task.random(rng, 4, 4, 6)};
        var first = Benchmark.predict(model, tasks, 1);
        var repeated = Benchmark.predict(model, tasks, 5);
        assertEquals(2, first.samples());
        assertEquals(10, repeated.samples());
        assertEquals(5 * first.correct(), repeated.correct());
        assertEquals(5 * first.checksum(), repeated.checksum());
    }

    @Test
    void rejectsIncompletePairOfCheckpoints() {
        assertThrows(IllegalArgumentException.class, () -> Benchmark.main(new String[]{"memory.bin"}));
    }
}
