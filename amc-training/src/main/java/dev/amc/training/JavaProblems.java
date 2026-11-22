package dev.amc.training;

import dev.amc.core.JavaLanguageModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Authored examples for a bounded experiment on two-step integer expressions. */
public final class JavaProblems {
    public static final String SIGNATURE = "static int solve(int x, int y)";

    public enum Split { TRAIN, NEW_INPUTS, NEW_FAMILIES }

    public enum Domain {
        ARITHMETIC(SIGNATURE, "results/java-code.bin", 32),
        ARRAYS("static int solve(int[] values)", "results/java-arrays.bin", 64),
        STRINGS("static int solve(String text)", "results/java-strings.bin", 80),
        COLLECTIONS("static int solve(java.util.List<Integer> values)", "results/java-collections.bin", 80);

        private final String signature;
        private final String checkpoint;
        private final int maxTokens;

        Domain(String signature, String checkpoint, int maxTokens) {
            this.signature = signature;
            this.checkpoint = checkpoint;
            this.maxTokens = maxTokens;
        }

        public String signature() { return signature; }
        public String checkpoint() { return checkpoint; }
        public int maxTokens() { return maxTokens; }

        public String prompt(String description) {
            return description + "\n" + signature;
        }

        public List<Problem> problems(Split split) {
            return switch (this) {
                case ARITHMETIC -> JavaProblems.problems(split);
                case ARRAYS -> JavaArrayProblems.problems(split);
                case STRINGS -> JavaStringProblems.problems(split);
                case COLLECTIONS -> JavaCollectionProblems.problems(split);
            };
        }

        public List<JavaLanguageModel.Example> training() {
            return problems(Split.TRAIN).stream().map(Problem::example).toList();
        }
    }

    public record Invocation(List<Domain> domains, String checkpoint, int width, String[] arguments) {
        public static Invocation parse(String[] args) {
            String flag = args.length > 0 ? args[0] : "";
            if (flag.equals("--all")) {
                return new Invocation(List.of(Domain.values()), "results/java-course.bin", 48, withoutFlag(args));
            }
            Domain domain = switch (flag) {
                case "--arrays" -> Domain.ARRAYS;
                case "--strings" -> Domain.STRINGS;
                case "--collections" -> Domain.COLLECTIONS;
                default -> Domain.ARITHMETIC;
            };
            return new Invocation(List.of(domain), domain.checkpoint(), 32,
                    domain == Domain.ARITHMETIC ? args : withoutFlag(args));
        }

        private static String[] withoutFlag(String[] args) {
            return Arrays.copyOfRange(args, 1, args.length);
        }

        public List<JavaLanguageModel.Example> training() {
            return domains.stream().flatMap(domain -> domain.training().stream()).toList();
        }
    }

    static Split split(boolean newFamily, int family, int index, int count) {
        if (newFamily) return Split.NEW_FAMILIES;
        return index == family % count || index == (family + count / 2) % count
                ? Split.NEW_INPUTS : Split.TRAIN;
    }

    private enum Operation {
        ADD("add", "+", "and", "to"),
        SUBTRACT("subtract", "-", "from", "from"),
        MULTIPLY("multiply", "*", "by", "by");

        private final String verb, symbol, connector, nextConnector;

        Operation(String verb, String symbol, String connector, String nextConnector) {
            this.verb = verb;
            this.symbol = symbol;
            this.connector = connector;
            this.nextConnector = nextConnector;
        }

        String firstInstruction(String left, String right) {
            if (this == SUBTRACT) return verb + " " + right + " " + connector + " " + left;
            return verb + " " + left + " " + connector + " " + right;
        }

        String secondInstruction(int constant) {
            if (this == MULTIPLY) return verb + " the result " + nextConnector + " " + constant;
            return verb + " " + constant + " " + nextConnector + " the result";
        }

        int apply(int left, int right) {
            return switch (this) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
            };
        }
    }

    /** Benchmark input expressions are authored by the datasets. */
    public record Check(String arguments, int expected) {}

    public record Problem(Domain domain, String family, String description, JavaLanguageModel.Example example,
                          List<Check> checks) {}

    private static final int[][] INPUTS = {
            {0, 0}, {7, 3}, {3, 7}, {-4, 9}, {0, -7}, {5, 5}, {12, -3},
            {Integer.MAX_VALUE, 7}, {Integer.MIN_VALUE, -3}
    };

    private JavaProblems() {}

    public static String prompt(String description) {
        return Domain.ARITHMETIC.prompt(description);
    }

    public static List<JavaLanguageModel.Example> training() {
        return Domain.ARITHMETIC.training();
    }

    public static List<Problem> problems(Split selected) {
        var result = new ArrayList<Problem>();
        var operations = Operation.values();
        for (Operation first : operations) {
            for (Operation second : operations) {
                boolean newFamily = (first == Operation.ADD && second == Operation.SUBTRACT)
                        || (first == Operation.MULTIPLY && second == Operation.ADD);
                int family = first.ordinal() * operations.length + second.ordinal();
                int firstHeldOut = 1 + family;
                int secondHeldOut = 1 + (family + 4) % 9;
                for (int constant = 1; constant <= 9; constant++) {
                    // Hold out the literal in both operand orders while exposing it in other families.
                    boolean newInputs = constant == firstHeldOut || constant == secondHeldOut;
                    Split split = newFamily ? Split.NEW_FAMILIES : newInputs ? Split.NEW_INPUTS : Split.TRAIN;
                    if (split != selected) continue;
                    for (boolean reverse : new boolean[]{false, true}) {
                        result.add(problem(first, second, reverse, constant));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static Problem problem(Operation first, Operation second, boolean reverse, int constant) {
        String left = reverse ? "y" : "x";
        String right = reverse ? "x" : "y";
        String description = "First " + first.firstInstruction(left, right)
                + ". Then " + second.secondInstruction(constant) + ".";
        String body = "return (" + left + " " + first.symbol + " " + right + ") "
                + second.symbol + " " + constant + ";";
        var checks = new ArrayList<Check>();
        for (int[] input : INPUTS) {
            int x = input[0], y = input[1];
            int intermediate = reverse ? first.apply(y, x) : first.apply(x, y);
            checks.add(new Check(x + ", " + y, second.apply(intermediate, constant)));
        }
        return new Problem(Domain.ARITHMETIC, first.verb + "-" + second.verb, description,
                new JavaLanguageModel.Example(prompt(description), body), List.copyOf(checks));
    }
}
