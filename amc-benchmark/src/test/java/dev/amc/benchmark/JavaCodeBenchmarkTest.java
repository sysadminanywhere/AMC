package dev.amc.benchmark;

import dev.amc.core.JavaLanguageModel;
import dev.amc.training.JavaProblems;
import org.junit.jupiter.api.Test;

import static dev.amc.benchmark.JavaCodeBenchmark.Result.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaCodeBenchmarkTest {
    @Test
    void classifiesCorrectWrongInvalidAndDisallowedGenerations() throws Exception {
        var problem = JavaProblems.problems(JavaProblems.Split.NEW_FAMILIES).getFirst();
        assertEquals(PASSED, JavaCodeBenchmark.check(new JavaLanguageModel.Generation(problem.example().body(), true), problem));
        assertEquals(WRONG_RESULT, JavaCodeBenchmark.check(new JavaLanguageModel.Generation("return 0;", true), problem));
        assertEquals(COMPILE_ERROR, JavaCodeBenchmark.check(new JavaLanguageModel.Generation("return (x + ;", true), problem));
        assertEquals(OUTSIDE_SUBSET, JavaCodeBenchmark.check(new JavaLanguageModel.Generation("return Math.abs(x);", true), problem));
        assertEquals(INCOMPLETE, JavaCodeBenchmark.check(new JavaLanguageModel.Generation("return x;", false), problem));
    }

    @Test
    void runsArrayReductionsButRejectsUnboundedLoopsAndCalls() throws Exception {
        var problem = JavaProblems.Domain.ARRAYS.problems(JavaProblems.Split.NEW_FAMILIES).getFirst();
        var correct = new JavaLanguageModel.Generation(problem.example().body(), true);
        var wrong = new JavaLanguageModel.Generation("return 0;", true);
        var unbounded = new JavaLanguageModel.Generation("for (;;) {} return 0;", true);
        var call = new JavaLanguageModel.Generation("return java.util.Arrays.hashCode(values);", true);
        assertEquals(PASSED, JavaCodeBenchmark.check(correct, problem));
        assertEquals(WRONG_RESULT, JavaCodeBenchmark.check(wrong, problem));
        assertEquals(OUTSIDE_SUBSET, JavaCodeBenchmark.check(unbounded, problem));
        assertEquals(OUTSIDE_SUBSET, JavaCodeBenchmark.check(call, problem));
    }

    @Test
    void checksCharacterIndicesAndDistinctListElements() throws Exception {
        var strings = JavaProblems.Domain.STRINGS.problems(JavaProblems.Split.NEW_FAMILIES).stream()
                .filter(problem -> problem.family().equals("LAST_EQUAL")).findFirst().orElseThrow();
        var collections = JavaProblems.Domain.COLLECTIONS.problems(JavaProblems.Split.NEW_FAMILIES).stream()
                .filter(problem -> problem.family().equals("DISTINCT_COUNT_ABOVE")).findFirst().orElseThrow();
        assertEquals(PASSED, JavaCodeBenchmark.check(new JavaLanguageModel.Generation(strings.example().body(), true), strings));
        assertEquals(PASSED, JavaCodeBenchmark.check(new JavaLanguageModel.Generation(collections.example().body(), true), collections));
        assertEquals(OUTSIDE_SUBSET, JavaCodeBenchmark.check(
                new JavaLanguageModel.Generation("return text.toCharArray(value);", true), strings));
        assertEquals(OUTSIDE_SUBSET, JavaCodeBenchmark.check(
                new JavaLanguageModel.Generation("Set<Integer> seen = new HashSet<>(values); return 0;", true), collections));
    }
}
