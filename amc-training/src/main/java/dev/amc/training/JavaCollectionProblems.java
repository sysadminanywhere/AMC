package dev.amc.training;

import dev.amc.core.JavaLanguageModel;
import dev.amc.training.JavaProblems.Check;
import dev.amc.training.JavaProblems.Domain;
import dev.amc.training.JavaProblems.Problem;
import dev.amc.training.JavaProblems.Split;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

final class JavaCollectionProblems {
    private enum Match {
        ABOVE("greater than", ">"), BELOW("less than", "<"), EQUAL("equal to", "==");

        final String description;
        final String operator;

        Match(String description, String operator) {
            this.description = description;
            this.operator = operator;
        }

        boolean matches(int actual, int target) {
            return switch (this) {
                case ABOVE -> actual > target;
                case BELOW -> actual < target;
                case EQUAL -> actual == target;
            };
        }
    }

    private enum Reduction {
        COUNT("Count the list elements", "count the matches", false, "1", Match.EQUAL),
        DISTINCT_COUNT("Count the distinct list elements", "count the distinct matches", true, "1", Match.ABOVE),
        DISTINCT_SUM("Sum the distinct list elements", "sum the distinct matches", true, "value", Match.BELOW);

        final String instruction, alternative, increment;
        final boolean distinct;
        final Match heldOut;

        Reduction(String instruction, String alternative, boolean distinct, String increment, Match heldOut) {
            this.instruction = instruction;
            this.alternative = alternative;
            this.distinct = distinct;
            this.increment = increment;
            this.heldOut = heldOut;
        }

        int expected(IntStream matching) {
            return switch (this) {
                case COUNT -> (int) matching.count();
                case DISTINCT_COUNT -> (int) matching.distinct().count();
                case DISTINCT_SUM -> matching.distinct().sum();
            };
        }
    }

    static List<Problem> problems(Split selected) {
        var result = new ArrayList<Problem>();
        var matches = Match.values();
        for (Reduction reduction : Reduction.values()) {
            for (Match match : matches) {
                int family = reduction.ordinal() * matches.length + match.ordinal();
                for (int index = 0; index < 9; index++) {
                    if (JavaProblems.split(reduction.heldOut == match, family, index, 9) == selected) {
                        add(result, reduction, match, index - 4);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Problem> result, Reduction reduction, Match match, int threshold) {
        String setup = reduction.distinct ? "Set<Integer> seen = new HashSet<>();" : "";
        String deduplicate = reduction.distinct ? " && seen.add(value)" : "";
        String body = """
                int result = 0;
                %s
                for (int value : values) {
                    if (value %s %d%s) {
                        result += %s;
                    }
                }
                return result;
                """.formatted(setup, match.operator, threshold, deduplicate, reduction.increment);
        int[][] inputs = {
                {}, {threshold}, {threshold - 1}, {threshold + 1},
                {threshold - 1, threshold, threshold + 1, threshold},
                {threshold - 2, threshold - 2, threshold + 2, threshold + 2},
                {Integer.MIN_VALUE, Integer.MAX_VALUE, -7, 7, -7, 7},
                {0, 1, -1, 0, 1, -1}
        };
        List<Check> checks = Arrays.stream(inputs).map(values -> {
            String arguments = Arrays.toString(values);
            int expected = reduction.expected(Arrays.stream(values).filter(value -> match.matches(value, threshold)));
            return new Check("List.of(" + arguments.substring(1, arguments.length() - 1) + ")", expected);
        }).toList();
        String condition = match.description + " " + threshold;
        for (String description : List.of(reduction.instruction + " " + condition + ".",
                "For list elements " + condition + ", " + reduction.alternative + ".")) {
            result.add(new Problem(Domain.COLLECTIONS, reduction.name() + "_" + match.name(), description,
                    new JavaLanguageModel.Example(Domain.COLLECTIONS.prompt(description), body), checks));
        }
    }
}
