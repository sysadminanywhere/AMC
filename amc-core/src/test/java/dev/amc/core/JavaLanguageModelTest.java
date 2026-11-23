package dev.amc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaLanguageModelTest {
    @Test
    void overflowingPromptVocabularyKeepsWordsAndFrequentFeatures() {
        var features = new java.util.LinkedHashMap<String, Integer>();
        features.put("bias", 1);
        features.put("word:rareIdentifier", 1);
        for (int i = 0; i < 5000; i++) features.put("position:" + i + ":x", 2);
        features.put("position:9000:popular", 3);
        var vocabulary = JavaLanguageModel.promptVocabulary(features);
        assertEquals(4096, vocabulary.size());
        assertEquals("bias", vocabulary.getFirst());
        assertEquals("word:rareIdentifier", vocabulary.get(1));
        assertEquals("position:9000:popular", vocabulary.get(2));
    }

    @Test
    void repeatGuardDistinguishesLongCyclesFromShortOrInterruptedRepeats() {
        var fragment = List.of(300, 301, 302, 303, 304);
        var output = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 7; i++) output.addAll(fragment);
        assertFalse(JavaLanguageModel.hasRepeatedTail(output));
        output.addAll(fragment);
        assertTrue(JavaLanguageModel.hasRepeatedTail(output));
        output.add(999);
        assertFalse(JavaLanguageModel.hasRepeatedTail(output));
    }

    @Test
    void subwordWeightsKeepTheirMergesAndDecodedSource(@TempDir Path directory) throws IOException {
        String source = "return \"Ω  Ω\";\n";
        var first = new JavaLanguageModel.Example("Return a string", source);
        var second = new JavaLanguageModel.Example("Generate a string", source);
        var model = JavaLanguageModel.initializeSubwords(List.of(first, second), 16, 42, 0.008, 24);
        for (int i = 0; i < 320; i++) model.train(first);
        var result = model.decode(first.prompt(), 64);
        assertEquals(source, result.generation().body());
        assertTrue(result.generation().complete());
        assertEquals(model.tokenCount(source), result.tokens());

        Path path = directory.resolve("subword.bin");
        model.save(path);
        var restored = JavaLanguageModel.load(path);
        assertTrue(restored.sourceMode());
        assertEquals(result, restored.decode(first.prompt(), 64));
        assertTrue(restored.tokenCount(source) < JavaTokens.sourceTokens(source).size());
    }

    @Test
    void sourceModePreservesWhitespaceAndCheckpointFormat(@TempDir Path directory) throws IOException {
        String source = "return \"two  spaces Ω\";\r\n";
        assertEquals(source, String.join("", JavaTokens.sourceTokens(source)));
        var example = new JavaLanguageModel.Example("Return a string", source);
        var model = JavaLanguageModel.initializeSource(List.of(example), 16, 42, 0.005);
        for (int i = 0; i < 320; i++) model.train(example);
        assertEquals(new JavaLanguageModel.Generation(source, true), model.generate(example.prompt(), 64));
        Path file = directory.resolve("source.bin");
        model.save(file);
        var restored = JavaLanguageModel.load(file);
        assertTrue(restored.sourceMode());
        assertEquals(model.generate(example.prompt(), 64), restored.generate(example.prompt(), 64));
    }

    private static final JavaLanguageModel.Example ADD =
            new JavaLanguageModel.Example("Add x and y", "return x + y;");
    private static final JavaLanguageModel.Example SUBTRACT =
            new JavaLanguageModel.Example("Subtract y from x", "return x - y;");

    @Test
    void recurrentGradientsMatchFiniteDifferences() {
        var model = JavaLanguageModel.initialize(List.of(ADD, SUBTRACT), 3, 42);
        assertGradients(model);
    }

    @Test
    void attentionGradientsMatchFiniteDifferencesAndWeightsRoundTrip(@TempDir Path directory) throws IOException {
        var model = JavaLanguageModel.initializeSubwords(List.of(ADD, SUBTRACT), 3, 42, 0.008, 12, true);
        assertGradients(model);
        var bias = model.parameters().getLast();
        double initial = bias.value;
        model.train(ADD);
        assertNotEquals(initial, bias.value);

        Path checkpoint = directory.resolve("attention.bin");
        model.save(checkpoint);
        var restored = JavaLanguageModel.load(checkpoint);
        assertEquals(model.parameterCount(), restored.parameterCount());
        assertEquals(model.loss(SUBTRACT, false), restored.loss(SUBTRACT, false));
        assertEquals(model.decode(ADD.prompt(), 32), restored.decode(ADD.prompt(), 32));
    }

    private static void assertGradients(JavaLanguageModel model) {
        model.loss(ADD, true);
        for (Scalar parameter : model.parameters()) {
            double original = parameter.value;
            parameter.value = original + 1e-5;
            double plus = model.loss(ADD, false);
            parameter.value = original - 1e-5;
            double minus = model.loss(ADD, false);
            parameter.value = original;
            assertEquals((plus - minus) / 2e-5, parameter.grad, 1e-7);
        }
    }

    @Test
    void generationRespondsToPromptAndSurvivesCheckpointRoundTrip(@TempDir Path directory) throws IOException {
        var model = JavaLanguageModel.initialize(List.of(ADD, SUBTRACT), 12, 42);
        double initial = model.loss(ADD, false);
        for (int i = 0; i < 180; i++) {
            model.train(ADD);
            model.train(SUBTRACT);
        }
        assertTrue(model.loss(ADD, false) < initial * 0.1);
        assertEquals(new JavaLanguageModel.Generation("return x + y ;", true), model.generate(ADD.prompt(), 12));
        assertEquals(new JavaLanguageModel.Generation("return x - y ;", true), model.generate(SUBTRACT.prompt(), 12));
        assertFalse(model.generate(ADD.prompt(), 2).complete());

        Path file = directory.resolve("model.bin");
        model.save(file);
        var restored = JavaLanguageModel.load(file);
        assertEquals(model.generate(SUBTRACT.prompt(), 12), restored.generate(SUBTRACT.prompt(), 12));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> JavaLanguageModel.load(file));
    }
}
