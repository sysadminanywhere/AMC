package dev.amc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @Test
    void scalarGradientIncludesBothPathsToTheInput() {
        Scalar x = Scalar.of(0.7);
        Scalar y = Scalar.of(-0.4);
        x.times(y).plus(x.tanh()).backward();
        double numerical = (function(0.7 + 1e-5) - function(0.7 - 1e-5)) / (2e-5);
        assertEquals(numerical, x.grad, 1e-7);
    }

    private static double function(double x) {
        return x * -0.4 + Math.tanh(x);
    }

    @Test
    void modelGradientsMatchFiniteDifferences() {
        var model = new AmcModel(3, 4, 5, 2, true, 17);
        var task = new Task(List.of(new Task.Assignment(0, 1), new Task.Assignment(1, 2)), 0, 1);
        model.loss(task).backward();
        int nonzero = 0;
        for (Scalar parameter : model.parameters()) {
            double original = parameter.value;
            parameter.value = original + 1e-5;
            double plus = model.loss(task).value;
            parameter.value = original - 1e-5;
            double minus = model.loss(task).value;
            parameter.value = original;
            assertEquals((plus - minus) / (2e-5), parameter.grad, 1e-6);
            if (parameter.grad != 0) nonzero++;
        }
        assertTrue(nonzero > 0);
    }

    @Test
    void readsLastWriteToQueriedKey() {
        var memory = new AmcModel(3, 4, 5, 2, true, 17);
        var baseline = new AmcModel(3, 4, 5, 2, false, 17);
        var writes = List.of(new Task.Assignment(0, 0), new Task.Assignment(0, 1), new Task.Assignment(1, 2));
        var query = new Task(writes, 0, 1);
        var queryLast = new Task(writes, 1, 2);
        assertArrayEquals(logits(memory, new Task(List.of(writes.get(1)), 0, 1)), logits(memory, query));
        assertArrayEquals(logits(memory, queryLast), logits(baseline, query));
        assertFalse(Arrays.equals(logits(memory, query), logits(baseline, query)));
    }

    @Test
    void generatedTargetMatchesLastWriteForTheKey() {
        var rng = new SplittableRandom(5);
        for (int i = 0; i < 100; i++) {
            var task = Task.random(rng, 5, 6, 7);
            int expected = -1;
            for (var assignment : task.assignments()) {
                if (assignment.key() == task.queryKey()) expected = assignment.value();
            }
            assertEquals(expected, task.targetValue());
        }
    }

    @Test
    void checkpointRestoresWeightsAndRejectsTruncationWithoutChangingModel(@TempDir Path directory)
            throws IOException {
        Path checkpoint = directory.resolve("weights.bin");
        var model = new AmcModel(3, 4, 5, 2, true, 17);
        var restored = new AmcModel(3, 4, 5, 2, true, 999);
        var task = new Task(List.of(new Task.Assignment(0, 1)), 0, 1);
        ModelIO.save(model, checkpoint);
        ModelIO.load(restored, checkpoint);
        assertArrayEquals(logits(model, task), logits(restored, task));

        byte[] bytes = Files.readAllBytes(checkpoint);
        Files.write(checkpoint, Arrays.copyOf(bytes, bytes.length - 1));
        var untouched = new AmcModel(3, 4, 5, 2, true, 333);
        double[] expected = logits(untouched, task);
        assertThrows(IOException.class, () -> ModelIO.load(untouched, checkpoint));
        assertArrayEquals(expected, logits(untouched, task));
    }

    private static double[] logits(AmcModel model, Task task) {
        return Arrays.stream(model.logits(task)).mapToDouble(logit -> logit.value).toArray();
    }
}
