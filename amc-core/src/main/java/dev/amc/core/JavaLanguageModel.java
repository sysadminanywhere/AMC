package dev.amc.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Prompt-conditioned recurrent decoder trained with next-token cross-entropy. */
public final class JavaLanguageModel {
    private static final int MAGIC = 0x414D4A31; // AMJ1
    private static final int SOURCE_MAGIC = 0x414D4A32; // AMJ2
    private static final int BPE_MAGIC = 0x414D4A33; // AMJ3
    private static final List<String> RESERVED = List.of("<start>", "<end>");
    private static final int START = 0;
    private static final int END = 1;
    private static final int MAX_WIDTH = 128;
    private static final int MAX_VOCABULARY = 4096;
    public static final double DEFAULT_LEARNING_RATE = 0.008;

    public record Example(String prompt, String body) {}
    public record Generation(String body, boolean complete) {}
    public record Decoding(Generation generation, int tokens) {}

    private final List<String> promptVocabulary;
    private final List<String> codeVocabulary;
    private final Map<String, Integer> promptIndex;
    private final Map<String, Integer> codeIndex;
    private final List<Scalar> parameters = new ArrayList<>();
    private final Scalar[][] promptWeights;
    private final Scalar[][] tokenWeights;
    private final Scalar[][] recurrent;
    private final Scalar[][] decoder;
    private final Scalar[][] decoderBias;
    private final int width;
    private final double learningRate;
    private final boolean sourceMode;
    private final BytePairTokenizer tokenizer;
    private Adam optimizer;

    public static JavaLanguageModel initialize(List<Example> training, int width, long seed) {
        return initialize(training, width, seed, DEFAULT_LEARNING_RATE);
    }

    public static JavaLanguageModel initialize(List<Example> training, int width, long seed, double learningRate) {
        return initialize(training, width, seed, learningRate, false, null);
    }

    public static JavaLanguageModel initializeSource(List<Example> training, int width, long seed, double learningRate) {
        return initialize(training, width, seed, learningRate, true, null);
    }

    public static JavaLanguageModel initializeSubwords(List<Example> training, int width, long seed,
                                                       double learningRate, int maxMerges) {
        var tokenizer = BytePairTokenizer.train(training.stream().map(Example::body).toList(), maxMerges);
        return initialize(training, width, seed, learningRate, true, tokenizer);
    }

    private static JavaLanguageModel initialize(List<Example> training, int width, long seed, double learningRate,
                                                boolean sourceMode, BytePairTokenizer tokenizer) {
        if (training.isEmpty()) throw new IllegalArgumentException("Training examples are required");
        var prompts = new LinkedHashSet<String>();
        var code = new LinkedHashSet<>(RESERVED);
        if (sourceMode && tokenizer == null) {
            code.addAll(List.of("\n", "\r", "\t"));
            for (char character = ' '; character <= '~'; character++) code.add(String.valueOf(character));
        }
        for (var example : training) {
            prompts.addAll(JavaTokens.promptFeatures(example.prompt()));
            if (tokenizer == null) code.addAll(codeTokens(example.body(), sourceMode));
        }
        return new JavaLanguageModel(List.copyOf(prompts), List.copyOf(code), width, seed, learningRate,
                sourceMode, tokenizer);
    }

    private JavaLanguageModel(List<String> prompts, List<String> code, int width, long seed, double learningRate,
                              boolean sourceMode, BytePairTokenizer tokenizer) {
        int outputs = tokenizer == null ? code.size() : RESERVED.size() + tokenizer.size();
        if (width < 1 || width > MAX_WIDTH || prompts.isEmpty() || prompts.size() > MAX_VOCABULARY
                || outputs < 3 || outputs > MAX_VOCABULARY) {
            throw new IllegalArgumentException("Invalid model width or vocabulary size");
        }
        if (!Double.isFinite(learningRate) || learningRate <= 0) {
            throw new IllegalArgumentException("Learning rate must be finite and positive");
        }
        this.width = width;
        this.learningRate = learningRate;
        this.sourceMode = sourceMode;
        this.tokenizer = tokenizer;
        this.promptVocabulary = prompts;
        this.codeVocabulary = code;
        this.promptIndex = index(prompts);
        this.codeIndex = index(code);
        var random = new SplittableRandom(seed);
        double scale = 1.0 / Math.sqrt(width);
        promptWeights = weights(prompts.size(), width, 0.15, random);
        tokenWeights = weights(outputs, width, 0.20, random);
        recurrent = weights(width, width, scale, random);
        decoder = weights(outputs, width, scale, random);
        decoderBias = weights(outputs, 1, 0, random);
    }

    private static Map<String, Integer> index(List<String> vocabulary) {
        var result = new HashMap<String, Integer>();
        for (int i = 0; i < vocabulary.size(); i++) {
            if (result.put(vocabulary.get(i), i) != null) throw new IllegalArgumentException("Duplicate token");
        }
        return result;
    }

    private Scalar[][] weights(int rows, int columns, double scale, SplittableRandom random) {
        var matrix = new Scalar[rows][columns];
        for (var row : matrix) {
            for (int i = 0; i < columns; i++) {
                row[i] = Scalar.of((random.nextDouble() * 2 - 1) * scale);
                parameters.add(row[i]);
            }
        }
        return matrix;
    }

    public int parameterCount() {
        return parameters.size();
    }

    List<Scalar> parameters() {
        return List.copyOf(parameters);
    }

    public double train(Example example) {
        if (optimizer == null) optimizer = new Adam(parameters, learningRate);
        double loss = loss(example, true);
        if (!Double.isFinite(loss)) throw new ArithmeticException("Non-finite language-model loss");
        optimizer.update();
        return loss;
    }

    double loss(Example example, boolean gradients) {
        int[] features = features(example.prompt());
        double[] context = context(features);
        int[] tokens = targetTokens(example.body());
        int[] sequence = new int[tokens.length + 2];
        sequence[0] = START;
        sequence[sequence.length - 1] = END;
        System.arraycopy(tokens, 0, sequence, 1, tokens.length);

        int steps = sequence.length - 1;
        double[][] states = new double[steps + 1][];
        double[][] probabilities = new double[steps][];
        states[0] = new double[width];
        double loss = 0;
        for (int step = 0; step < steps; step++) {
            states[step + 1] = transition(states[step], sequence[step], context);
            var scores = scores(states[step + 1]);
            loss += normalize(scores, sequence[step + 1]);
            probabilities[step] = scores;
        }
        if (gradients) backpropagate(features, sequence, states, probabilities);
        return loss / steps;
    }

    private void backpropagate(int[] features, int[] sequence, double[][] states, double[][] probabilities) {
        for (Scalar parameter : parameters) parameter.grad = 0;
        double[] contextGrad = new double[width];
        double[] stateGrad = new double[width];
        double[] previousGrad = new double[width];
        int steps = probabilities.length;
        for (int step = steps - 1; step >= 0; step--) {
            double[] state = states[step + 1];
            double[] previous = states[step];
            for (int token = 1; token < decoder.length; token++) {
                double delta = probabilities[step][token];
                if (token == sequence[step + 1]) delta--;
                delta /= steps;
                decoderBias[token][0].grad += delta;
                for (int i = 0; i < width; i++) {
                    decoder[token][i].grad += delta * state[i];
                    stateGrad[i] += delta * decoder[token][i].value;
                }
            }
            Arrays.fill(previousGrad, 0);
            for (int row = 0; row < width; row++) {
                double delta = stateGrad[row] * (1 - state[row] * state[row]);
                contextGrad[row] += delta;
                tokenWeights[sequence[step]][row].grad += delta;
                for (int col = 0; col < width; col++) {
                    recurrent[row][col].grad += delta * previous[col];
                    previousGrad[col] += delta * recurrent[row][col].value;
                }
            }
            double[] reuse = stateGrad;
            stateGrad = previousGrad;
            previousGrad = reuse;
        }
        double scale = 1.0 / Math.sqrt(features.length);
        for (int feature : features) {
            for (int i = 0; i < width; i++) promptWeights[feature][i].grad += scale * contextGrad[i];
        }
    }

    private int[] features(String prompt) {
        return JavaTokens.promptFeatures(prompt).stream().map(promptIndex::get)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).toArray();
    }

    private double[] context(int[] features) {
        double[] context = new double[width];
        double scale = 1.0 / Math.sqrt(features.length);
        for (int feature : features) {
            for (int i = 0; i < width; i++) context[i] += scale * promptWeights[feature][i].value;
        }
        return context;
    }

    private double[] transition(double[] previous, int token, double[] context) {
        var state = new double[width];
        for (int row = 0; row < width; row++) {
            double sum = context[row] + tokenWeights[token][row].value;
            for (int col = 0; col < width; col++) sum += recurrent[row][col].value * previous[col];
            state[row] = Math.tanh(sum);
        }
        return state;
    }

    private double[] scores(double[] state) {
        double[] scores = new double[decoder.length];
        for (int token = 1; token < decoder.length; token++) {
            double sum = decoderBias[token][0].value;
            for (int i = 0; i < width; i++) sum += decoder[token][i].value * state[i];
            scores[token] = sum;
        }
        return scores;
    }

    private static double normalize(double[] scores, int target) {
        double correct = scores[target];
        double max = scores[1];
        for (int i = 2; i < scores.length; i++) max = Math.max(max, scores[i]);
        double sum = 0;
        for (int i = 1; i < scores.length; i++) {
            scores[i] = Math.exp(scores[i] - max);
            sum += scores[i];
        }
        for (int i = 1; i < scores.length; i++) scores[i] /= sum;
        return max + Math.log(sum) - correct;
    }

    public Generation generate(String prompt, int maxTokens) {
        return decode(prompt, maxTokens).generation();
    }

    public Decoding decode(String prompt, int maxTokens) {
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be positive");
        double[] context = context(features(prompt));
        double[] state = new double[width];
        var output = new ArrayList<Integer>();
        int token = START;
        for (int i = 0; i < maxTokens; i++) {
            state = transition(state, token, context);
            double[] scores = scores(state);
            token = END;
            for (int j = 2; j < scores.length; j++) {
                if (scores[j] > scores[token]) token = j;
            }
            if (token == END) return new Decoding(new Generation(join(output), true), output.size());
            output.add(token);
        }
        return new Decoding(new Generation(join(output), false), output.size());
    }

    private static List<String> codeTokens(String text, boolean sourceMode) {
        return sourceMode ? JavaTokens.sourceTokens(text) : JavaTokens.tokenize(text);
    }

    private int[] targetTokens(String source) {
        if (tokenizer != null) {
            int[] ids = tokenizer.encode(source);
            for (int i = 0; i < ids.length; i++) ids[i] += RESERVED.size();
            return ids;
        }
        var tokens = codeTokens(source, sourceMode);
        int[] ids = new int[tokens.size()];
        int i = 0;
        for (String token : tokens) {
            Integer id = codeIndex.get(token);
            if (id == null) throw new IllegalArgumentException("Unknown target token: " + token);
            ids[i++] = id;
        }
        return ids;
    }

    public int tokenCount(String source) {
        return tokenizer == null ? codeTokens(source, sourceMode).size() : tokenizer.encode(source).length;
    }

    private String join(List<Integer> tokens) {
        if (tokenizer == null) {
            return String.join(sourceMode ? "" : " ", tokens.stream().map(codeVocabulary::get).toList());
        }
        return tokenizer.decode(tokens.stream().mapToInt(id -> id - RESERVED.size()).toArray());
    }

    public boolean sourceMode() {
        return sourceMode;
    }

    public void save(Path path) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "amc-java-", ".bin");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(tokenizer != null ? BPE_MAGIC : sourceMode ? SOURCE_MAGIC : MAGIC);
                out.writeInt(width);
                writeVocabulary(out, promptVocabulary);
                if (tokenizer == null) writeVocabulary(out, codeVocabulary);
                else tokenizer.write(out);
                for (Scalar parameter : parameters) out.writeDouble(parameter.value);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static JavaLanguageModel load(Path path) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != SOURCE_MAGIC && magic != BPE_MAGIC) {
                throw new IOException("Invalid Java language model checkpoint");
            }
            int width = in.readInt();
            List<String> prompt = readVocabulary(in);
            var tokenizer = magic == BPE_MAGIC ? BytePairTokenizer.read(in) : null;
            List<String> code = tokenizer == null ? readVocabulary(in) : RESERVED;
            if (!prompt.contains("bias") || code.size() < RESERVED.size()
                    || !code.subList(0, RESERVED.size()).equals(RESERVED)) throw new IOException("Missing reserved tokens");
            var model = new JavaLanguageModel(prompt, code, width, 0, DEFAULT_LEARNING_RATE, magic != MAGIC, tokenizer);
            for (Scalar parameter : model.parameters) {
                parameter.value = in.readDouble();
                if (!Double.isFinite(parameter.value)) throw new IOException("Non-finite model weight");
            }
            if (in.read() != -1) throw new IOException("Trailing bytes in checkpoint");
            return model;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid Java language model configuration", ex);
        }
    }

    private static void writeVocabulary(DataOutputStream out, List<String> vocabulary) throws IOException {
        out.writeInt(vocabulary.size());
        for (String token : vocabulary) out.writeUTF(token);
    }

    private static List<String> readVocabulary(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > MAX_VOCABULARY) throw new IOException("Invalid vocabulary size");
        var vocabulary = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) vocabulary.add(in.readUTF());
        return List.copyOf(vocabulary);
    }
}
