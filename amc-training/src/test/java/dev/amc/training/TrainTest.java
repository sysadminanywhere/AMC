package dev.amc.training;

import dev.amc.core.Adam;
import dev.amc.core.AmcModel;
import dev.amc.core.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class TrainTest {
    @Test
    void trainingGeneralizesToIndependentSamples() {
        var memory = new AmcModel(4, 4, 8, 2, true, 42);
        var baseline = new AmcModel(4, 4, 8, 2, false, 42);
        var before = Train.evaluate(memory, 4, 4, 6, 123, 256);
        var memoryParameters = memory.parameters();
        var baselineParameters = baseline.parameters();
        var memoryOptimizer = new Adam(memoryParameters, 0.015);
        var baselineOptimizer = new Adam(baselineParameters, 0.015);
        var rng = new SplittableRandom(456);
        for (int i = 0; i < 400; i++) {
            var task = Task.random(rng, 4, 4, 6);
            Train.update(memory, memoryParameters, memoryOptimizer, task);
            Train.update(baseline, baselineParameters, baselineOptimizer, task);
        }
        var after = Train.evaluate(memory, 4, 4, 6, 123, 256);
        var control = Train.evaluate(baseline, 4, 4, 6, 123, 256);
        assertTrue(after.loss() < before.loss() * 0.1, after::toString);
        assertTrue(after.accuracy() >= 0.98, after::toString);
        assertTrue(after.accuracy() - control.accuracy() >= 0.25, control::toString);
    }

    @Test
    void updateClearsGradientForEmbeddingDisconnectedFromTheCurrentExample() {
        var model = new AmcModel(2, 2, 3, 2, true, 42);
        var parameters = model.parameters();
        var optimizer = new Adam(parameters, 0.015);
        var first = new Task(List.of(new Task.Assignment(0, 0)), 0, 0);
        var second = new Task(List.of(new Task.Assignment(0, 1)), 0, 1);
        Train.update(model, parameters, optimizer, first);
        assertNotEquals(0.0, parameters.getFirst().grad);
        Train.update(model, parameters, optimizer, second);
        assertEquals(0.0, parameters.getFirst().grad);
    }

    @Test
    void optionsAcceptEvaluationOnlyAndRejectUnknownArguments() {
        var options = Train.Options.parse(new String[]{"--epochs=0", "--seed=8000000000", "--baseline"});
        assertEquals(0, options.epochs());
        assertEquals(8_000_000_000L, options.seed());
        assertFalse(options.memory());
        assertThrows(IllegalArgumentException.class, () -> Train.Options.parse(new String[]{"--epoch=10"}));
        assertThrows(IllegalArgumentException.class, () -> Train.Options.parse(new String[]{"--test-samples=0"}));
    }
}
