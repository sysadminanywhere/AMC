package dev.amc.training;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaScaffoldTest {
    @Test
    void extractsAndInsertsStatementsWithoutChangingDeclarations() {
        var scaffold = new JavaScaffold("""
                import java.util.List;
                class Sample {
                    private final int fallback = 7;
                    int size(List<Integer> input) { throw new UnsupportedOperationException(); }
                }
                """);
        String reference = """
                import java.util.List;
                class Sample {
                    private final int fallback = 7;
                    int size(List<Integer> input) { return input.isEmpty() ? fallback : input.size(); }
                }
                """;
        String body = scaffold.extractBody(reference);
        assertEquals("return input.isEmpty() ? fallback : input.size();", body);
        assertTrue(scaffold.bodyPrompt("Find the size").contains("Fill size"));
        assertTrue(scaffold.applyBody(body).contains("int size(List<Integer> input) {\n" + body));
        assertThrows(IllegalArgumentException.class, () -> scaffold.applyBody("} int other() { return 2;"));
        assertThrows(IllegalArgumentException.class, () -> scaffold.extractBody("import java.util.Set;\n" + reference));
    }

    @Test
    void keepsTheClassAndHeadersWhileAddingImportsFieldsAndBodies() {
        var scaffold = new JavaScaffold("""
                public class Box {
                    public Box(String value) { throw new UnsupportedOperationException(); }
                    public int size() { throw new UnsupportedOperationException(); }
                    public String label() { return "preserved"; }
                }
                """);
        String reference = """
                import java.util.List;
                final class Box {
                    private final List<String> items;
                    Box(String value) { items = List.of(value); }
                    int size() { return sizeOf(items); }
                    private int sizeOf(List<String> values) { return values.size(); }
                    public String label() { return "not copied"; }
                }
                """;
        String generated = scaffold.apply(scaffold.extract(reference));
        assertTrue(generated.contains("import java.util.List;"));
        assertTrue(generated.contains("public class Box"));
        assertTrue(generated.contains("public Box(String value) { items = List.of(value); }"));
        assertTrue(generated.contains("public int size() { return sizeOf(items); }"));
        assertTrue(generated.contains("public String label() { return \"preserved\"; }"));
        assertTrue(generated.contains("private final List<String> items;"));
        assertTrue(generated.contains("private int sizeOf(List<String> values)"));
        assertFalse(generated.contains("UnsupportedOperationException"));
    }

    @Test
    void matchesOverloadsByTypesAndRejectsRenamedParameters() {
        var scaffold = new JavaScaffold("""
                class Sample {
                    int size(String value) { throw new UnsupportedOperationException(); }
                    int size(int value) { throw new UnsupportedOperationException(); }
                }
                """);
        var reference = """
                class Sample {
                    int size(int value) { return value; }
                    int size(String value) { return value.length(); }
                }
                """;
        var generated = scaffold.apply(scaffold.extract(reference));
        assertTrue(generated.indexOf("return value.length()") < generated.indexOf("return value;"));
        assertThrows(IllegalArgumentException.class,
                () -> scaffold.extract(reference.replace("String value", "String other")));
    }

    @Test
    void rejectsExtraDeclarationsInsideBodyAndImportSections() {
        var scaffold = new JavaScaffold("""
                class Sample {
                    int read() { throw new UnsupportedOperationException(); }
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> scaffold.apply("{ return 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> scaffold.apply("\n@@\n\n@@\n{ return 1; } int injected() { return 2; }"));
        assertThrows(IllegalArgumentException.class,
                () -> scaffold.apply("class Injected {}\n@@\n\n@@\n{ return 1; }"));
    }

    @Test
    void fillsNestedConstructorsWithoutConfusingMethodsWithTheOuterClass() {
        var scaffold = new JavaScaffold("""
                class Sample {
                    int size() { throw new UnsupportedOperationException(); }
                    static class Item {
                        Item(int value) { throw new UnsupportedOperationException(); }
                        int size() { throw new UnsupportedOperationException(); }
                    }
                }
                """);
        var reference = """
                class Sample {
                    int size() { return 1; }
                    static class Item {
                        Item(int value) { System.out.println(value); }
                        int size() { return 2; }
                    }
                }
                """;
        String result = scaffold.apply(scaffold.extract(reference));
        assertTrue(result.contains("int size() { return 1; }"));
        assertTrue(result.contains("Item(int value) { System.out.println(value); }"));
        assertTrue(result.contains("int size() { return 2; }"));
        assertFalse(result.contains("UnsupportedOperationException"));
    }
}
