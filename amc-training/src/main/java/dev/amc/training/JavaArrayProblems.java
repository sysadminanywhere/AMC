package dev.amc.training;

import dev.amc.core.JavaLanguageModel;
import dev.amc.training.JavaProblems.Check;
import dev.amc.training.JavaProblems.Domain;
import dev.amc.training.JavaProblems.Problem;
import dev.amc.training.JavaProblems.Split;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Short array reductions with independently computed expected values. */
final class JavaArrayProblems {
    private enum Comparison {
        ABOVE("greater than", ">"), BELOW("less than", "<"),
        AT_LEAST("at least", ">="), EQUAL("equal to", "==");

        final String words;
        final String operator;

        Comparison(String words, String operator) {
            this.words = words;
            this.operator = operator;
        }

        boolean matches(int value, int threshold) {
            return switch (this) {
                case ABOVE -> value > threshold;
                case BELOW -> value < threshold;
                case AT_LEAST -> value >= threshold;
                case EQUAL -> value == threshold;
            };
        }
    }

    private enum Reduction {
        COUNT("Count the elements", "count the matches", "1"),
        SUM("Sum the elements", "sum their values", "value"),
        SUM_SQUARES("Sum the squares of the elements", "sum their squared values", "value * value");

        final String instruction;
        final String alternative;
        final String expression;

        Reduction(String instruction, String alternative, String expression) {
            this.instruction = instruction;
            this.alternative = alternative;
            this.expression = expression;
        }

        int contribution(int value) {
            return switch (this) {
                case COUNT -> 1;
                case SUM -> value;
                case SUM_SQUARES -> value * value;
            };
        }

        boolean isHeldOut(Comparison comparison) {
            return switch (this) {
                case COUNT -> comparison == Comparison.AT_LEAST;
                case SUM -> comparison == Comparison.BELOW;
                case SUM_SQUARES -> comparison == Comparison.ABOVE;
            };
        }
    }

    private static final int MIN_THRESHOLD = -6;
    private static final int THRESHOLD_COUNT = 13;

    static List<Problem> problems(Split selected) {
        var problems = new ArrayList<Problem>();
        var comparisons = Comparison.values();
        for (Reduction reduction : Reduction.values()) {
            for (Comparison comparison : comparisons) {
                int family = reduction.ordinal() * comparisons.length + comparison.ordinal();
                for (int index = 0; index < THRESHOLD_COUNT; index++) {
                    boolean newInput = index == family || index == (family + 6) % THRESHOLD_COUNT;
                    Split split = reduction.isHeldOut(comparison) ? Split.NEW_FAMILIES
                            : newInput ? Split.NEW_INPUTS : Split.TRAIN;
                    if (split == selected) addProblems(problems, reduction, comparison, index + MIN_THRESHOLD);
                }
            }
        }
        return List.copyOf(problems);
    }

    private static void addProblems(List<Problem> problems, Reduction reduction,
                                    Comparison comparison, int threshold) {
        String family = reduction.name() + "_" + comparison.name();
        String condition = comparison.words + " " + threshold;
        String body = """
                int result = 0;
                for (int value : values) {
                    if (value %s %d) {
                        result += %s;
                    }
                }
                return result;
                """.formatted(comparison.operator, threshold, reduction.expression);
        List<Check> checks = checks(reduction, comparison, threshold);
        for (String description : List.of(reduction.instruction + " " + condition + ".",
                "For elements " + condition + ", " + reduction.alternative + ".")) {
            var example = new JavaLanguageModel.Example(Domain.ARRAYS.prompt(description), body);
            problems.add(new Problem(Domain.ARRAYS, family, description, example, checks));
        }
    }

    private static List<Check> checks(Reduction reduction, Comparison comparison, int threshold) {
        int[][] inputs = {
                {}, {threshold}, {threshold - 1}, {threshold + 1},
                {threshold - 1, threshold, threshold + 1, threshold},
                {0, -7, 8, -3, 2, 0}, {Integer.MIN_VALUE, Integer.MAX_VALUE, 46341},
                {threshold + 1, threshold + 2, threshold - 1, threshold - 2}
        };
        return Arrays.stream(inputs).map(values -> new Check("new int[]" + Arrays.toString(values)
                .replace('[', '{').replace(']', '}'),
                Arrays.stream(values).filter(value -> comparison.matches(value, threshold))
                        .map(reduction::contribution).sum())).toList();
    }
}
