package dev.amc.training;

import dev.amc.core.JavaLanguageModel;
import dev.amc.training.JavaProblems.Check;
import dev.amc.training.JavaProblems.Domain;
import dev.amc.training.JavaProblems.Problem;
import dev.amc.training.JavaProblems.Split;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

final class JavaStringProblems {
    private enum Match {
        EQUAL("equal to", "=="), BELOW("before", "<"), ABOVE("after", ">");

        final String description;
        final String operator;

        Match(String description, String operator) {
            this.description = description;
            this.operator = operator;
        }

        boolean matches(int actual, char target) {
            return switch (this) {
                case EQUAL -> actual == target;
                case BELOW -> actual < target;
                case ABOVE -> actual > target;
            };
        }
    }

    private enum Query {
        COUNT("Count characters", "count the matches", "int result = 0;",
                "result += 1;", "", "result", Match.ABOVE),
        FIRST("Find the first index of a character", "return the first matching index",
                "int index = 0;", "return index;", "index += 1;", "-1", Match.BELOW),
        LAST("Find the last index of a character", "return the last matching index",
                "int index = 0; int result = -1;", "result = index;", "index += 1;", "result", Match.EQUAL);

        final String instruction, alternative, setup, onMatch, afterCharacter, result;
        final Match heldOut;

        Query(String instruction, String alternative, String setup, String onMatch,
              String afterCharacter, String result, Match heldOut) {
            this.instruction = instruction;
            this.alternative = alternative;
            this.setup = setup;
            this.onMatch = onMatch;
            this.afterCharacter = afterCharacter;
            this.result = result;
            this.heldOut = heldOut;
        }

        int expected(String text, IntPredicate match) {
            var matching = IntStream.range(0, text.length()).filter(index -> match.test(text.charAt(index)));
            return switch (this) {
                case COUNT -> (int) matching.count();
                case FIRST -> matching.findFirst().orElse(-1);
                case LAST -> matching.max().orElse(-1);
            };
        }
    }

    static List<Problem> problems(Split selected) {
        var result = new ArrayList<Problem>();
        var matches = Match.values();
        for (Query query : Query.values()) {
            for (Match match : matches) {
                int family = query.ordinal() * matches.length + match.ordinal();
                for (int index = 0; index < 9; index++) {
                    if (JavaProblems.split(query.heldOut == match, family, index, 9) == selected) {
                        add(result, query, match, (char) ('a' + index));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Problem> result, Query query, Match match, char target) {
        String condition = match.description + " '" + target + "'";
        String body = """
                %s
                for (char ch : text.toCharArray()) {
                    if (ch %s '%c') {
                        %s
                    }
                    %s
                }
                return %s;
                """.formatted(query.setup, match.operator, target, query.onMatch, query.afterCharacter, query.result);
        String targetText = Character.toString(target);
        List<Check> checks = List.of("", targetText, "0", "z", targetText + "0" + targetText + "z",
                "z0" + targetText, "aaiiz", "Ω" + targetText + " ").stream()
                .map(text -> new Check("\"" + text + "\"", query.expected(text, value -> match.matches(value, target))))
                .toList();
        for (String description : List.of(query.instruction + " " + condition + ".",
                "For characters " + condition + ", " + query.alternative + ".")) {
            result.add(new Problem(Domain.STRINGS, query.name() + "_" + match.name(), description,
                    new JavaLanguageModel.Example(Domain.STRINGS.prompt(description), body), checks));
        }
    }
}
