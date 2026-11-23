package dev.amc.training;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Keeps existing Java declarations and replaces only explicit Exercism placeholders. */
public final class JavaScaffold {
    private static final String SEPARATOR = "\n@@\n";
    private static final Pattern SECTIONS = Pattern.compile("(?m)^[ \\t]*@@[ \\t]*\\r?$");

    private record Span(int start, int end) {}
    private record Edit(Span span, String value) {}
    private record Slot(List<String> owners, MethodTree method) {
        String label() {
            String name = method.getReturnType() == null ? owners.getLast() : method.getName().toString();
            return owners.size() == 1 ? name : owners.getLast() + "." + name;
        }
    }
    private record MethodKey(List<String> owners, Signature signature) {}
    private record Signature(String name, List<String> parameters) {
        static Signature of(MethodTree method) {
            return new Signature(method.getName().toString(),
                    method.getParameters().stream().map(parameter -> parameter.getType().toString()).toList());
        }
    }
    private record Parsed(String source, CompilationUnitTree unit, ClassTree type, SourcePositions positions) {
        @SuppressWarnings("removal") // Keep the JDK 21 SourcePositions API.
        Span span(Tree tree) {
            int start = Math.toIntExact(positions.getStartPosition(unit, tree));
            int end = Math.toIntExact(positions.getEndPosition(unit, tree));
            if (start < 0 || end < start || end > source.length()) throw new IllegalArgumentException("Unknown source span");
            return new Span(start, end);
        }

        String text(Tree tree) {
            var range = span(tree);
            return source.substring(range.start(), range.end());
        }
    }

    private final Parsed starter;
    private final List<Slot> slots;

    public JavaScaffold(String source) {
        starter = parse(source);
        var methods = new ArrayList<Slot>();
        collectMethods(starter.type(), List.of(), methods);
        slots = methods.stream().filter(slot -> isPlaceholder(slot.method())).toList();
        if (slots.isEmpty()) throw new IllegalArgumentException("Starter has no supported placeholders");
    }

    public String prompt(String description) {
        var names = slots.stream().map(Slot::label).toList();
        return "Fill the Java starter. Output imports, helper members, and method bodies in order, "
                + "separated by lines containing @@. Body order: " + String.join(", ", names) + ".\n"
                + instructions(description);
    }

    public String bodyPrompt(String description) {
        Slot slot = singleMethod();
        return "Fill " + slot.label() + " in the Java starter. Output only the statements inside the method body, "
                + "without the outer braces.\n" + instructions(description);
    }

    private static String instructions(String description) {
        int newline = description.indexOf('\n');
        return description.startsWith("Write the complete ") && newline >= 0
                ? description.substring(newline + 1) : description;
    }

    private Slot singleMethod() {
        if (slots.size() != 1 || slots.getFirst().method().getReturnType() == null) {
            throw new IllegalArgumentException("Expected exactly one method placeholder");
        }
        return slots.getFirst();
    }

    public String extractBody(String reference) {
        singleMethod();
        String[] sections = SECTIONS.split(extract(reference), -1);
        if (!sections[0].isBlank() || !sections[1].isBlank()) {
            throw new IllegalArgumentException("Reference requires imports or helper members");
        }
        String body = sections[2].strip();
        return body.substring(1, body.length() - 1).strip();
    }

    public String applyBody(String statements) {
        Span span = starter.span(singleMethod().method().getBody());
        String body = "{\n" + statements + "\n}";
        requireBody(body, 1);
        String candidate = starter.source().substring(0, span.start()) + body + starter.source().substring(span.end());
        parse(candidate);
        return candidate;
    }

    public String extract(String reference) {
        Parsed parsed = parse(reference);
        if (!parsed.type().getSimpleName().contentEquals(starter.type().getSimpleName())) {
            throw new IllegalArgumentException("Reference declares a different class");
        }
        Map<MethodKey, MethodTree> methods = new HashMap<>();
        var declarations = new ArrayList<Slot>();
        collectMethods(parsed.type(), List.of(), declarations);
        for (var declaration : declarations) {
            methods.put(new MethodKey(declaration.owners(), Signature.of(declaration.method())), declaration.method());
        }
        Set<String> starterImports = new HashSet<>();
        starter.unit().getImports().forEach(tree -> starterImports.add(tree.toString()));
        var sections = new ArrayList<String>();
        sections.add(String.join("\n", parsed.unit().getImports().stream()
                .filter(tree -> !starterImports.contains(tree.toString())).map(parsed::text).toList()));
        var existing = new HashSet<>();
        starter.type().getMembers().forEach(member -> existing.add(memberKey(member)));
        var members = parsed.type().getMembers().stream().filter(member -> !existing.contains(memberKey(member)))
                .map(parsed::span).sorted(Comparator.comparingInt(Span::start)).toList();
        sections.add(memberText(parsed.source(), members));
        for (var slot : slots) {
            MethodTree method = slot.method();
            MethodTree implementation = methods.get(new MethodKey(slot.owners(), Signature.of(method)));
            if (implementation == null || implementation.getBody() == null
                    || !parameterNames(method).equals(parameterNames(implementation))) {
                throw new IllegalArgumentException("Reference cannot fill " + slot.label() + " " + Signature.of(method).parameters());
            }
            sections.add(parsed.text(implementation.getBody()));
        }
        if (sections.stream().anyMatch(section -> SECTIONS.matcher(section).find())) {
            throw new IllegalArgumentException("Source contains reserved section delimiter");
        }
        return String.join(SEPARATOR, sections);
    }

    public String apply(String generated) {
        String[] sections = SECTIONS.split(generated, -1);
        if (sections.length != slots.size() + 2) throw new IllegalArgumentException("Incorrect number of edit sections");

        Parsed imports = parse(sections[0] + "\nclass AmcImports {}");
        if (!imports.type().getSimpleName().contentEquals("AmcImports") || !imports.type().getMembers().isEmpty()) {
            throw new IllegalArgumentException("Import section must contain only imports");
        }
        String importText = String.join("", imports.unit().getImports().stream().map(ImportTree::toString).toList());

        Parsed helpers = parse("class " + starter.type().getSimpleName() + " {\n" + sections[1] + "\n}");
        if (!helpers.unit().getImports().isEmpty()) throw new IllegalArgumentException("Helper section escaped its class");
        var edits = new ArrayList<Edit>();
        int classEnd = starter.span(starter.type()).end() - 1;
        edits.add(new Edit(new Span(classEnd, classEnd), "\n" + sections[1] + "\n"));
        for (int i = 0; i < slots.size(); i++) {
            String body = sections[i + 2].strip();
            requireBody(body, i + 1);
            edits.add(new Edit(starter.span(slots.get(i).method().getBody()), body));
        }

        var result = new StringBuilder(starter.source());
        edits.sort(Comparator.comparingInt((Edit edit) -> edit.span().start()).reversed());
        for (var edit : edits) result.replace(edit.span().start(), edit.span().end(), edit.value());
        result.insert(0, importText);
        String candidate = result.toString();
        parse(candidate);
        return candidate;
    }

    private static void requireBody(String body, int section) {
        Parsed wrapper = parse("class AmcBody { void slot() " + body + "\n}");
        if (wrapper.type().getMembers().size() != 1
                || !(wrapper.type().getMembers().getFirst() instanceof MethodTree method)
                || method.getBody() == null || !wrapper.text(method.getBody()).equals(body)) {
            throw new IllegalArgumentException("Expected one method body in section " + section);
        }
    }

    private static void collectMethods(ClassTree type, List<String> parents, List<Slot> methods) {
        var path = new ArrayList<>(parents);
        path.add(type.getSimpleName().toString());
        for (Tree member : type.getMembers()) {
            if (member instanceof MethodTree method) methods.add(new Slot(List.copyOf(path), method));
            else if (member instanceof ClassTree nested) collectMethods(nested, path, methods);
        }
    }

    private static boolean isPlaceholder(MethodTree method) {
        var body = method.getBody();
        return body != null && body.getStatements().size() == 1
                && body.getStatements().getFirst() instanceof ThrowTree statement
                && statement.getExpression() instanceof NewClassTree exception
                && exception.getIdentifier().toString().equals("UnsupportedOperationException");
    }

    private static List<String> parameterNames(MethodTree method) {
        return method.getParameters().stream().map(parameter -> parameter.getName().toString()).toList();
    }

    private static Object memberKey(Tree member) {
        return switch (member) {
            case MethodTree method -> Signature.of(method);
            case VariableTree field -> "field:" + field.getName();
            case ClassTree type -> "type:" + type.getSimpleName();
            default -> member.toString();
        };
    }

    private static String memberText(String source, List<Span> spans) {
        var output = new StringBuilder();
        int end = -1;
        for (Span span : spans) {
            if (span.end() <= end) continue;
            if (span.start() >= end && !output.isEmpty()) output.append('\n');
            output.append(source, Math.max(end, span.start()), span.end());
            end = span.end();
        }
        return output.toString();
    }

    private static Parsed parse(String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required to inspect Java scaffolds");
        var file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null, List.of(file));
            var unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    || unit.getPackageName() != null || unit.getTypeDecls().size() != 1
                    || !(unit.getTypeDecls().getFirst() instanceof ClassTree type) || type.getKind() != Tree.Kind.CLASS) {
                throw new IllegalArgumentException("Expected one syntactically valid Java class");
            }
            return new Parsed(source, unit, type, Trees.instance(task).getSourcePositions());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
