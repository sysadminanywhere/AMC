package dev.amc.core;

/** Dot-product attention over the known lexical features of one prompt. */
final class PromptAttention {
    record Read(double[] query, double[] weights, double[] context) {}

    private final Scalar[][] embeddings;
    private final double[][] values;
    private final Scalar[] queryBias;
    private final double scale;

    PromptAttention(Scalar[][] embeddings, Scalar[] queryBias) {
        this.embeddings = embeddings;
        this.queryBias = queryBias;
        this.scale = 1.0 / Math.sqrt(queryBias.length);
        values = new double[embeddings.length][queryBias.length];
        for (int term = 0; term < embeddings.length; term++) {
            for (int i = 0; i < queryBias.length; i++) values[term][i] = embeddings[term][i].value;
        }
    }

    Read read(double[] previous) {
        var query = new double[previous.length];
        for (int i = 0; i < query.length; i++) query[i] = previous[i] + queryBias[i].value;

        var weights = new double[values.length];
        double max = Double.NEGATIVE_INFINITY;
        for (int term = 0; term < values.length; term++) {
            weights[term] = scale * dot(query, values[term]);
            max = Math.max(max, weights[term]);
        }
        double sum = 0;
        for (int term = 0; term < weights.length; term++) {
            weights[term] = Math.exp(weights[term] - max);
            sum += weights[term];
        }
        var context = new double[query.length];
        for (int term = 0; term < weights.length; term++) {
            weights[term] /= sum;
            for (int i = 0; i < context.length; i++) context[i] += weights[term] * values[term][i];
        }
        return new Read(query, weights, context);
    }

    void backward(Read read, double[] contextGrad, double[] previousGrad) {
        double weightedGradient = dot(contextGrad, read.context());
        double[] queryGrad = new double[contextGrad.length];
        for (int term = 0; term < values.length; term++) {
            double weight = read.weights()[term];
            // The same embedding contributes as the value and as the key in the softmax.
            double scoreGrad = scale * weight * (dot(contextGrad, values[term]) - weightedGradient);
            var embedding = embeddings[term];
            for (int i = 0; i < contextGrad.length; i++) {
                embedding[i].grad += weight * contextGrad[i] + scoreGrad * read.query()[i];
                queryGrad[i] += scoreGrad * values[term][i];
            }
        }
        for (int i = 0; i < queryGrad.length; i++) {
            previousGrad[i] += queryGrad[i];
            queryBias[i].grad += queryGrad[i];
        }
    }

    private static double dot(double[] first, double[] second) {
        double sum = 0;
        for (int i = 0; i < first.length; i++) sum += first[i] * second[i];
        return sum;
    }
}
