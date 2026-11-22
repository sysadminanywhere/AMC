package dev.amc.training;

import dev.amc.core.JavaTokens;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JavaProblemsTest {
    @Test
    void promptsAndHeldOutFamiliesStayOutsideTraining() {
        for (var domain : JavaProblems.Domain.values()) verifySplits(domain);
        assertTrue(Collections.disjoint(familyLiterals(JavaProblems.Split.TRAIN),
                familyLiterals(JavaProblems.Split.NEW_INPUTS)));
    }

    @Test
    void fullCourseLoadsTrainingExamplesFromEveryDomain() {
        var course = JavaProblems.Invocation.parse(new String[]{"--all", "weights.bin"});
        assertEquals(JavaProblems.Domain.values().length, course.domains().size());
        assertArrayEquals(new String[]{"weights.bin"}, course.arguments());
        var training = new HashSet<>(course.training());
        assertEquals(course.training().size(), training.size());
        for (var domain : course.domains()) {
            assertTrue(training.containsAll(domain.training()));
            for (var problem : domain.problems(JavaProblems.Split.NEW_FAMILIES)) {
                assertFalse(training.contains(problem.example()));
            }
        }
    }

    private static void verifySplits(JavaProblems.Domain domain) {
        var prompts = new HashSet<String>();
        var previousBodies = new HashSet<String>();
        for (var split : JavaProblems.Split.values()) {
            var problems = domain.problems(split);
            assertFalse(problems.isEmpty());
            for (var problem : problems) assertTrue(prompts.add(problem.example().prompt()));
            var bodies = problems.stream().map(problem -> problem.example().body()).collect(Collectors.toSet());
            assertTrue(Collections.disjoint(previousBodies, bodies));
            previousBodies.addAll(bodies);
        }
        var training = families(domain, JavaProblems.Split.TRAIN);
        assertTrue(training.containsAll(families(domain, JavaProblems.Split.NEW_INPUTS)));
        assertTrue(Collections.disjoint(training, families(domain, JavaProblems.Split.NEW_FAMILIES)));
    }

    private static Set<String> families(JavaProblems.Domain domain, JavaProblems.Split split) {
        return domain.problems(split).stream().map(JavaProblems.Problem::family).collect(Collectors.toSet());
    }

    private static Set<String> familyLiterals(JavaProblems.Split split) {
        return JavaProblems.problems(split).stream().map(problem -> {
            var tokens = JavaTokens.tokenize(problem.example().body());
            return problem.family() + ":" + tokens.get(tokens.size() - 2);
        }).collect(Collectors.toSet());
    }
}
