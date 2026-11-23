package dev.amc.training;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Produces and audits a deterministic method-body corpus for a paired scale experiment. */
public final class JavaBodyScaleCorpus {
    private static final int VARIANTS = 50;
    private static final int CHECK_VARIANTS = 4;
    private static final int[] VALUES = {-7, -1, 0, 1, 7, 25, 50, 51};
    private static final String[] STRINGS = {"", "azAZ", "aaab", "bBbB", " xyz ", "m", "Aa a", "zyXX"};
    private static final String SYMBOLS = "abcdefghijklmnopqrstuvwxyz0123456789!#$%&()*+,-./:";
    private static final String ITEM = "    record Item(int price, int quantity, String label) {}\n";

    private enum Domain {
        LIST("java.util.List<Integer> values"), ARRAY("int[] values"), TEXT("String text"), ITEM("Item item");

        private final String argument;

        Domain(String argument) {
            this.argument = argument;
        }
    }

    private record Variant(String oracleArgument, int expected) {}
    private record Task(String split, String family, String prompt, String starter, String solution, String tests) {}

    private JavaBodyScaleCorpus() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: JavaBodyScaleCorpus destination");
        Path destination = Files.createDirectory(Path.of(args[0]));
        var small = new ArrayList<Task>();
        var large = new ArrayList<Task>();
        for (Domain domain : Domain.values()) {
            for (int family = 0; family < 7; family++) {
                String split = family < 5 ? "train" : family == 5 ? "validation" : "test";
                var variants = new ArrayList<Task>();
                int count = family < 5 ? VARIANTS : CHECK_VARIANTS;
                for (int index = 0; index < count; index++) {
                    int value = family < 5 ? index + 1 : 2 + index * 15;
                    variants.add(task(domain, family, split, value));
                }
                Collections.shuffle(variants, new Random(42L + domain.ordinal() * 7 + family));
                large.addAll(variants);
                int smallCount = family < 5 ? 12 + (domain.ordinal() * 5 + family) % 2 : count;
                small.addAll(variants.subList(0, smallCount));
            }
        }
        audit(large);
        write(destination.resolve("250"), small);
        write(destination.resolve("1000"), large);
        System.out.printf("audited=%d trainSmall=250 trainLarge=1000 validation=16 test=16%n", large.size());
    }

    private static Task task(Domain domain, int family, String split, int bound) {
        String starter = "class Solution {\n" + (domain == Domain.ITEM ? ITEM : "")
                + "    static int solve(" + domain.argument + ") { throw new UnsupportedOperationException(); }\n}\n";
        char letter = SYMBOLS.charAt(bound - 1);
        String description = description(domain, family, bound, letter);
        String body = body(domain, family, bound, letter);
        String solution = starter.replace("throw new UnsupportedOperationException();", body);
        var tests = new StringBuilder("class Verify {\n    public static void main(String[] args) {\n");
        for (Variant variant : checks(domain, family, bound, letter)) {
            tests.append("        if (Solution.solve(").append(variant.oracleArgument()).append(") != ")
                    .append(variant.expected()).append(") throw new AssertionError();\n");
        }
        return new Task(split, domain.name().toLowerCase(Locale.ROOT) + "-" + family,
                description + "\n" + starter, starter, solution, tests.append("    }\n}\n").toString());
    }

    private static String description(Domain domain, int family, int n, char c) {
        return switch (domain) {
            case LIST -> List.of("Count distinct values strictly greater than " + n + ".",
                    "Sum distinct values strictly less than " + n + ".",
                    "Return 1 if the list contains " + n + ", else 0.", "Count occurrences of " + n + " in the list.",
                    "Count all values greater than or equal to " + n + ".",
                    "Sum distinct values greater than or equal to " + n + ".",
                    "Count distinct values strictly less than " + n + ".").get(family);
            case ARRAY -> List.of("Count elements strictly greater than " + n + ".",
                    "Sum elements strictly less than " + n + ".", "Count elements equal to " + n + ".",
                    "Return the first index of " + n + " or -1 if absent.",
                    "Sum squares of elements greater than " + n + ".",
                    "Count elements no greater than " + n + ".",
                    "Return the last index of an element at least " + n + " or -1 if absent.").get(family);
            case TEXT -> List.of("Count occurrences of '" + c + "'.",
                    "Return the first index of '" + c + "' or -1 if absent.",
                    "Return the last index of '" + c + "' or -1 if absent.",
                    "Count characters different from '" + c + "'.", "Return 1 if text starts with '" + c + "', else 0.",
                    "Return 1 if text ends with '" + c + "', else 0.",
                    "Count matching pairs of consecutive '" + c + "' characters, including overlapping pairs.").get(family);
            case ITEM -> List.of("Add " + n + " to item.price().", "Multiply item.quantity() by " + n + ".",
                    "Return item.price() times item.quantity(), plus " + n + ".",
                    "Add " + n + " to the length of item.label().", "Return 1 if item.price() exceeds " + n + ", else 0.",
                    "Return 1 if item.quantity() is below " + n + ", else 0.",
                    "Return item.price() times the smaller of its quantity and " + n + ".").get(family);
        };
    }

    private static String body(Domain domain, int family, int n, char c) {
        String template = switch (domain) {
            case LIST -> List.of(
                    "return (int) values.stream().filter(v -> v > %d).distinct().count();",
                    "return values.stream().filter(v -> v < %d).distinct().mapToInt(Integer::intValue).sum();",
                    "return values.contains(%d) ? 1 : 0;",
                    "return java.util.Collections.frequency(values, %d);",
                    "return (int) values.stream().filter(v -> v >= %d).count();",
                    "return values.stream().filter(v -> v >= %d).distinct().mapToInt(Integer::intValue).sum();",
                    "return (int) values.stream().filter(v -> v < %d).distinct().count();").get(family);
            case ARRAY -> List.of(
                    "int result = 0; for (int v : values) if (v > %d) result++; return result;",
                    "int result = 0; for (int v : values) if (v < %d) result += v; return result;",
                    "int result = 0; for (int v : values) if (v == %d) result++; return result;",
                    "for (int i = 0; i < values.length; i++) if (values[i] == %d) return i; return -1;",
                    "int result = 0; for (int v : values) if (v > %d) result += v * v; return result;",
                    "int result = 0; for (int v : values) if (v <= %d) result++; return result;",
                    "for (int i = values.length - 1; i >= 0; i--) if (values[i] >= %d) return i; return -1;")
                    .get(family);
            case TEXT -> List.of(
                    "int result = 0; for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '%1$c') result++; return result;",
                    "return text.indexOf('%1$c');", "return text.lastIndexOf('%1$c');",
                    "int result = 0; for (int i = 0; i < text.length(); i++) if (text.charAt(i) != '%1$c') result++; return result;",
                    "return text.startsWith(\"%1$c\") ? 1 : 0;", "return text.endsWith(\"%1$c\") ? 1 : 0;",
                    "int result = 0; for (int i = 1; i < text.length(); i++) if (text.charAt(i - 1) == '%1$c' && text.charAt(i) == '%1$c') result++; return result;")
                    .get(family);
            case ITEM -> List.of("return item.price() + %d;", "return item.quantity() * %d;",
                    "return item.price() * item.quantity() + %d;", "return item.label().length() + %d;",
                    "return item.price() > %d ? 1 : 0;", "return item.quantity() < %d ? 1 : 0;",
                    "return item.price() * Math.min(item.quantity(), %d);").get(family);
        };
        return String.format(Locale.ROOT, template, domain == Domain.TEXT ? c : n);
    }

    private static List<Variant> checks(Domain domain, int family, int n, char c) {
        var checks = new ArrayList<Variant>();
        for (int i = 0; i < VALUES.length; i++) {
            int value = VALUES[i];
            switch (domain) {
                case LIST -> {
                    List<Integer> input = switch (i) {
                        case 0 -> List.of();
                        case 1 -> List.of(n);
                        case 2 -> List.of(n - 1, n - 1, n - 2);
                        case 3 -> List.of(n - 1, n, n + 1, n);
                        default -> List.of(value, n + i, n, -i, value, n);
                    };
                    var distinct = new java.util.HashSet<>(input);
                    int expected = switch (family) {
                        case 0 -> (int) distinct.stream().filter(v -> v > n).count();
                        case 1 -> distinct.stream().filter(v -> v < n).reduce(0, Integer::sum);
                        case 2 -> distinct.contains(n) ? 1 : 0;
                        case 3 -> (int) input.stream().filter(v -> v == n).count();
                        case 4 -> (int) input.stream().filter(v -> v >= n).count();
                        case 5 -> distinct.stream().filter(v -> v >= n).reduce(0, Integer::sum);
                        default -> (int) distinct.stream().filter(v -> v < n).count();
                    };
                    checks.add(new Variant("java.util.List.of(" + elements(input.toString()) + ")", expected));
                }
                case ARRAY -> {
                    int[] input = switch (i) {
                        case 0 -> new int[0];
                        case 1 -> new int[]{n};
                        case 2 -> new int[]{n - 1, n - 2};
                        case 3 -> new int[]{n - 1, n, n + 1, n};
                        default -> new int[]{value, n + i, n, -i, value, n};
                    };
                    int expected = switch (family) {
                        case 0 -> (int) Arrays.stream(input).filter(v -> v > n).count();
                        case 1 -> Arrays.stream(input).filter(v -> v < n).sum();
                        case 2 -> (int) Arrays.stream(input).filter(v -> v == n).count();
                        case 3 -> java.util.stream.IntStream.range(0, input.length).filter(j -> input[j] == n).findFirst().orElse(-1);
                        case 4 -> Arrays.stream(input).filter(v -> v > n).map(v -> v * v).sum();
                        case 5 -> (int) Arrays.stream(input).filter(v -> v <= n).count();
                        default -> java.util.stream.IntStream.range(0, input.length).filter(j -> input[j] >= n).max().orElse(-1);
                    };
                    checks.add(new Variant("new int[]{" + elements(Arrays.toString(input)) + "}", expected));
                }
                case TEXT -> {
                    String text = switch (i) {
                        case 0 -> "";
                        case 1 -> String.valueOf(c);
                        case 2 -> STRINGS[i] + c;
                        case 3 -> c + STRINGS[i];
                        case 4 -> ("" + c).repeat(3) + STRINGS[i] + c;
                        default -> STRINGS[i];
                    };
                    int expected = switch (family) {
                        case 0 -> (int) text.chars().filter(ch -> ch == c).count();
                        case 1 -> java.util.stream.IntStream.range(0, text.length()).filter(j -> text.charAt(j) == c).min().orElse(-1);
                        case 2 -> java.util.stream.IntStream.range(0, text.length()).filter(j -> text.charAt(j) == c).max().orElse(-1);
                        case 3 -> text.length() - (int) text.chars().filter(ch -> ch == c).count();
                        case 4 -> !text.isEmpty() && text.charAt(0) == c ? 1 : 0;
                        case 5 -> !text.isEmpty() && text.charAt(text.length() - 1) == c ? 1 : 0;
                        default -> (int) java.util.stream.IntStream.range(0, text.length() - 1)
                                .filter(j -> text.charAt(j) == c && text.charAt(j + 1) == c).count();
                    };
                    checks.add(new Variant("\"" + text + "\"", expected));
                }
                case ITEM -> {
                    int quantity = i % 2 == 0 ? n + i : i - 1;
                    int price = i == 7 ? n : value;
                    int expected = switch (family) {
                        case 0 -> n + price;
                        case 1 -> n * quantity;
                        case 2 -> n + quantity * price;
                        case 3 -> n + STRINGS[i].length();
                        case 4 -> price <= n ? 0 : 1;
                        case 5 -> quantity >= n ? 0 : 1;
                        default -> price * (quantity < n ? quantity : n);
                    };
                    checks.add(new Variant("new Solution.Item(" + price + ", " + quantity + ", \"" + STRINGS[i] + "\")", expected));
                }
            }
        }
        return checks;
    }

    private static String elements(String sequence) {
        return sequence.substring(1, sequence.length() - 1);
    }

    private static void write(Path root, List<Task> tasks) throws IOException {
        var manifest = new StringBuilder("id,split,family\n");
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            String id = Integer.toString(task.prompt().hashCode(), 16).replace("-", "n");
            Path directory = Files.createDirectories(root.resolve(task.split()).resolve(id));
            Files.writeString(directory.resolve("prompt.txt"), task.prompt(), java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.writeString(directory.resolve("Starter.java"), task.starter());
            Files.writeString(directory.resolve("Solution.java"), task.solution());
            Files.writeString(directory.resolve("Verify.java"), task.tests());
            manifest.append(id).append(',').append(task.split()).append(',').append(task.family()).append('\n');
        }
        Files.writeString(root.resolve("split.csv"), manifest);
    }

    private static void audit(List<Task> tasks) throws Exception {
        Path directory = Files.createTempDirectory("amc-authored-java-");
        try {
            var source = new StringBuilder();
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                var scaffold = new JavaScaffold(task.starter());
                String solution = scaffold.applyBody(scaffold.extractBody(task.solution()));
                source.append(solution.replace("Solution", "Solution" + i));
                source.append(task.tests().replace("Solution", "Solution" + i).replace("class Verify", "class Verify" + i));
            }
            Path file = Files.writeString(directory.resolve("References.java"), source);
            int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21",
                    "-d", directory.toString(), file.toString());
            if (status != 0) throw new IOException("Cannot compile authored references");
            try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()})) {
                for (int i = 0; i < tasks.size(); i++) {
                    var verify = loader.loadClass("Verify" + i).getDeclaredMethod("main", String[].class);
                    verify.setAccessible(true); // Batch audit classes use package access.
                    verify.invoke(null, (Object) new String[0]);
                }
            }
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(directory);
        }
    }
}
