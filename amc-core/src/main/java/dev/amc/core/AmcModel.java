package dev.amc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Exact key-to-slot routing with differentiable values and a fixed-step recurrent readout.
 * Training updates the value embedding, recurrent weights and decoder.
 */
public final class AmcModel {
    private final int keys, values, hidden, steps;
    private final boolean hasMemory;
    private final Scalar[][] embedding, recurrent, inputWeight, output;
    private final Scalar[] recurrentBias, outputBias;
    private final List<Scalar> params = new ArrayList<>();

    public AmcModel(int keys, int values, int hidden, int steps, boolean hasMemory, long seed) {
        if (keys<2 || values<2 || hidden<1 || steps<1) throw new IllegalArgumentException();
        this.keys=keys; this.values=values; this.hidden=hidden; this.steps=steps; this.hasMemory=hasMemory;
        var rng = new SplittableRandom(seed);
        embedding = weights(hidden, values, rng, 0.30);
        recurrent = weights(hidden, hidden, rng, 0.30/Math.sqrt(hidden));
        inputWeight = weights(hidden, hidden, rng, 0.6/Math.sqrt(hidden));
        output = weights(values, hidden, rng, 0.5/Math.sqrt(hidden));
        recurrentBias = biases(hidden);
        outputBias = biases(values);
    }
    private Scalar[][] weights(int rows, int cols, SplittableRandom rng, double scale) {
        Scalar[][] result = new Scalar[rows][cols];
        for(int i=0;i<rows;i++) for(int j=0;j<cols;j++) {
            result[i][j]=Scalar.of((rng.nextDouble()*2-1)*scale);
            params.add(result[i][j]);
        }
        return result;
    }
    private Scalar[] biases(int n) {
        Scalar[] result = new Scalar[n];
        for(int i=0;i<n;i++) { result[i]=Scalar.of(0); params.add(result[i]); }
        return result;
    }
    public List<Scalar> parameters() { return List.copyOf(params); }
    public int parameterCount() { return params.size(); }
    public boolean hasMemory() { return hasMemory; }

    private Scalar[] read(Task task) {
        if (task.queryKey()<0 || task.queryKey()>=keys) throw new IllegalArgumentException("query key");
        Scalar[][] memory = hasMemory ? new Scalar[keys][] : null;
        for(var write : task.assignments()) {
            if (write.key()<0 || write.key()>=keys || write.value()<0 || write.value()>=values)
                throw new IllegalArgumentException("assignment out of bounds");
            if (memory != null) memory[write.key()] = encode(write.value());
        }
        if (memory != null) {
            Scalar[] read = memory[task.queryKey()];
            if (read == null) throw new IllegalArgumentException("query key was never written");
            return read;
        }
        if (task.assignments().isEmpty()) return zeroState();
        return encode(task.assignments().getLast().value());
    }

    private Scalar[] encode(int value) {
        Scalar[] encoded = new Scalar[hidden];
        for (int j = 0; j < hidden; j++) encoded[j] = embedding[j][value].tanh();
        return encoded;
    }

    private Scalar[] zeroState() {
        Scalar[] state = new Scalar[hidden];
        for (int j = 0; j < hidden; j++) state[j] = Scalar.of(0);
        return state;
    }

    public Scalar[] logits(Task task) {
        Scalar[] read = read(task);
        Scalar[] state = zeroState();
        for(int step=0;step<steps;step++) {
            Scalar[] next = new Scalar[hidden];
            for(int i=0;i<hidden;i++) {
                Scalar sum = recurrentBias[i];
                for(int j=0;j<hidden;j++) {
                    sum=sum.plus(recurrent[i][j].times(state[j]));
                    sum=sum.plus(inputWeight[i][j].times(read[j]));
                }
                next[i] = sum.tanh();
            }
            state=next;
        }
        Scalar[] logits = new Scalar[values];
        for(int i=0;i<values;i++) {
            Scalar sum=outputBias[i];
            for(int j=0;j<hidden;j++) sum=sum.plus(output[i][j].times(state[j]));
            logits[i]=sum;
        }
        return logits;
    }
    public Scalar loss(Task task) {
        return crossEntropy(logits(task), task.targetValue());
    }

    private static Scalar crossEntropy(Scalar[] logits, int target) {
        double max = Double.NEGATIVE_INFINITY;
        for(Scalar logit : logits) max=Math.max(max, logit.value);
        Scalar expSum=Scalar.of(0);
        for(Scalar logit : logits) expSum=expSum.plus(logit.plus(-max).exp());
        return expSum.log().minus(logits[target].plus(-max));
    }

    public record Score(int prediction, double loss) {}

    public Score score(Task task) {
        Scalar[] scores = logits(task);
        return new Score(argmax(scores), crossEntropy(scores, task.targetValue()).value);
    }

    public int predict(Task task) {
        return argmax(logits(task));
    }

    private static int argmax(Scalar[] logits) {
        int best=0;
        for(int i=1;i<logits.length;i++) if(logits[i].value>logits[best].value) best=i;
        return best;
    }
}
